package vn.agriculture.soa.user.reputation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.agriculture.soa.user.account.*;
import vn.agriculture.soa.user.outbox.*;
import vn.agriculture.soa.user.profile.*;
import vn.agriculture.soa.user.reputation.ReputationDecision.*;

@Service
public class ReputationService {
    private final JdbcTemplate jdbc;
    private final ReputationDecision.Parser parser;
    private final ProfileRepository profiles;
    private final ReputationOutboxWriter reputationOutbox;
    private final AccountOutboxWriter accountOutbox;
    private final ProfileOutboxWriter profileOutbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    public ReputationService(JdbcTemplate jdbc,ReputationDecision.Parser parser,ProfileRepository profiles,
                             ReputationOutboxWriter reputationOutbox,AccountOutboxWriter accountOutbox,
                             ProfileOutboxWriter profileOutbox,PlatformTransactionManager manager,Clock clock) {
        this.jdbc=jdbc; this.parser=parser; this.profiles=profiles; this.reputationOutbox=reputationOutbox;
        this.accountOutbox=accountOutbox; this.profileOutbox=profileOutbox; this.tx=new TransactionTemplate(manager); this.clock=clock;
    }
    public boolean apply(String key,String payload) { return apply(parser.parse(key,payload)); }
    public boolean apply(ReputationDecision event) {
        return Boolean.TRUE.equals(tx.execute(status->{
            Data d=event.data();
            // Two-key advisory locks have a separate PostgreSQL namespace from media/Admin bigint locks.
            jdbc.query("SELECT pg_advisory_xact_lock(?,?)",rs->{},0x525054,event.eventId().hashCode());
            String eventHash=parser.fingerprint(event),businessHash=parser.fingerprint(d);
            var receipt=jdbc.queryForList("SELECT event_fingerprint FROM reputation_event_receipts WHERE event_id=?",String.class,event.eventId());
            if (!receipt.isEmpty()) {
                if (!receipt.getFirst().equals(eventHash)) { throw DecisionFailure.invalid("EVENT_ID_CONFLICT"); }
                return false;
            }
            var account=profiles.account(d.accountId(),true).orElseThrow(()->DecisionFailure.retry("ACCOUNT_NOT_FOUND"));
            ReputationPolicy.validate(account.role(),d);
            var old=jdbc.queryForList("SELECT decision_fingerprint FROM reputation_history WHERE account_id=? AND decision_id=? AND action=?",String.class,d.accountId(),d.decisionId(),d.action().name());
            if (!old.isEmpty()) {
                if (old.size()!=1 || !businessHash.equals(old.getFirst())) { throw DecisionFailure.invalid("DECISION_BUSINESS_KEY_CONFLICT"); }
                receipt(event,eventHash); return false;
            }
            if (d.action()!=Action.REVERSAL && jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE account_id=? AND decision_id=? AND action<>'REVERSAL'",
                    Integer.class,d.accountId(),d.decisionId())>0) { throw DecisionFailure.invalid("DECISION_BUSINESS_KEY_CONFLICT"); }
            var otherRoles=jdbc.queryForList("SELECT role FROM accounts WHERE account_id=?",String.class,d.counterpartyId());
            if (otherRoles.isEmpty()) { throw DecisionFailure.retry("COUNTERPARTY_NOT_FOUND"); }
            if (otherRoles.getFirst().equals("ADMIN") || otherRoles.getFirst().equals(account.role().name())) { throw DecisionFailure.invalid("COUNTERPARTY_ROLE_INVALID"); }
            var now=clock.instant();
            if (d.action()==Action.COMPLETION_REWARD && jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE account_id=? AND contract_id=? AND action='COMPLETION_REWARD'",Integer.class,d.accountId(),d.contractId())>0) {
                throw DecisionFailure.invalid("REWARD_BUSINESS_KEY_CONFLICT");
            }
            // A confirmed late penalty must follow reversal of this account's completion reward.
            String opposite=d.action()==Action.PENALTY?"COMPLETION_REWARD":"PENALTY";
            if (d.action()!=Action.REVERSAL && jdbc.queryForObject("""
                    SELECT count(*) FROM reputation_history h WHERE h.account_id=? AND h.contract_id=? AND h.action=?
                    AND NOT EXISTS (SELECT 1 FROM reputation_history r WHERE r.account_id=h.account_id AND r.original_decision_id=h.decision_id AND r.action='REVERSAL')
                    """,Integer.class,d.accountId(),d.contractId(),opposite)>0) { throw DecisionFailure.retry("CONTRACT_DECISION_ORDER_UNRESOLVED"); }
            BigDecimal before=jdbc.queryForObject("SELECT reputation_score FROM accounts WHERE account_id=?",BigDecimal.class,d.accountId());
            BigDecimal requested=d.requestedDelta();
            if (d.action()==Action.REVERSAL) { requested=reversalDelta(d); }
            if (d.action()==Action.COMPLETION_REWARD) {
                BigDecimal awarded=jdbc.queryForObject("""
                        SELECT coalesce(sum(greatest(actual_delta,0)),0) FROM reputation_history
                        WHERE account_id=? AND counterparty_id=? AND action='COMPLETION_REWARD' AND created_at>? AND created_at<=?
                        """,BigDecimal.class,d.accountId(),d.counterpartyId(),Timestamp.from(now.minus(Duration.ofDays(30))),Timestamp.from(now));
                requested=requested.min(BigDecimal.ONE.subtract(awarded).max(BigDecimal.ZERO));
            }
            BigDecimal after=ReputationPolicy.clamp(before.add(requested));
            jdbc.update("""
                    INSERT INTO reputation_history(history_id,account_id,counterparty_id,contract_id,decision_id,decision_version,original_decision_id,action,
                        requested_delta,actual_delta,before_score,after_score,reason_code,created_at,decision_payload,decision_fingerprint,effective_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                    """,UUID.randomUUID(),d.accountId(),d.counterpartyId(),d.contractId(),d.decisionId(),d.decisionVersion(),d.originalDecisionId(),d.action().name(),
                    d.requestedDelta(),after.subtract(before),before,after,d.reasonCode(),Timestamp.from(now),parser.dataJson(d),businessHash,Timestamp.from(d.effectiveAt()));
            boolean repLock=after.signum()==0 || d.action()!=Action.REVERSAL
                    && jdbc.queryForObject("SELECT reputation_lock FROM accounts WHERE account_id=?",Boolean.class,d.accountId());
            AccountStatus next=after.signum()==0 && account.status()!=AccountStatus.INACTIVE?AccountStatus.LOCKED:account.status();
            jdbc.update("UPDATE accounts SET reputation_score=?,reputation_lock=?,status=?,account_version=account_version+1,updated_at=? WHERE account_id=?",
                    after,repLock,next.name(),Timestamp.from(now),d.accountId());
            if (after.signum()==0) { jdbc.update("UPDATE refresh_tokens SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL",Timestamp.from(now),d.accountId()); }
            reputationOutbox.append(event,before,after,now);
            if (next!=account.status()) {
                long version=jdbc.queryForObject("SELECT account_version FROM accounts WHERE account_id=?",Long.class,d.accountId());
                String email=jdbc.queryForObject("SELECT email FROM accounts WHERE account_id=?",String.class,d.accountId());
                accountOutbox.appendStatusChange(d.accountId(),email,account.verifiedAt()!=null,next,d.reasonCode(),"REPUTATION_ZERO",version,now,event.actorId());
                if (account.status()==AccountStatus.ACTIVE) {
                    long profileVersion=profiles.touchAccount(d.accountId(),true,now);
                    profileOutbox.append(d.accountId(),account.role(),profileVersion,null,now,event.actorId());
                }
            }
            receipt(event,eventHash); return true;
        }));
    }
    private BigDecimal reversalDelta(Data d) {
        // Payment references the original base decision directly. Version is local to a decision,
        // never an ordering counter for the account or contract. Requested delta is audit input only.
        var originals=jdbc.queryForList("""
                SELECT contract_id,counterparty_id,actual_delta,decision_version FROM reputation_history
                WHERE account_id=? AND decision_id=? AND action IN ('PENALTY','COMPLETION_REWARD')
                """,d.accountId(),d.originalDecisionId());
        if (originals.isEmpty()) {
            if (jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE account_id=? AND decision_id=? AND action='REVERSAL'",
                    Integer.class,d.accountId(),d.originalDecisionId())>0) { throw DecisionFailure.invalid("REVERSAL_OF_REVERSAL_NOT_SUPPORTED"); }
            throw DecisionFailure.retry("ORIGINAL_DECISION_NOT_FOUND");
        }
        if (originals.size()!=1) { throw DecisionFailure.invalid("AMBIGUOUS_ORIGINAL_DECISION"); }
        var original=originals.getFirst();
        if (!d.contractId().equals(original.get("contract_id")) || !d.counterpartyId().equals(original.get("counterparty_id"))
                || ((Number)original.get("decision_version")).longValue()!=1) { throw DecisionFailure.invalid("REVERSAL_ORIGINAL_MISMATCH"); }
        if (jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE account_id=? AND original_decision_id=? AND action='REVERSAL'",
                Integer.class,d.accountId(),d.originalDecisionId())>0) { throw DecisionFailure.invalid("ORIGINAL_ALREADY_REVERSED"); }
        return ((BigDecimal)original.get("actual_delta")).negate();
    }
    private void receipt(ReputationDecision event,String hash) {
        jdbc.update("INSERT INTO reputation_event_receipts(event_id,account_id,decision_id,event_fingerprint,processed_at) VALUES (?,?,?,?,?)",
                event.eventId(),event.data().accountId(),event.data().decisionId(),hash,Timestamp.from(clock.instant()));
    }
}
