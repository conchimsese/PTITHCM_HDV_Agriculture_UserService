package vn.agriculture.soa.user.reputation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ReputationOutboxWriter {
    public static final String TOPIC="soa.user.reputation.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ReputationOutboxWriter(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private record Data(UUID accountId,UUID contractId,UUID decisionId,String action,BigDecimal delta,
                        BigDecimal beforeScore,BigDecimal afterScore,String reasonCode) { }
    private record Envelope(UUID eventId,String eventType,int eventVersion,String sourceService,Instant occurredAt,
                            UUID actorId,List<UUID> recipientIds,String referenceType,UUID referenceId,Data data) { }
    public void append(ReputationDecision decision,BigDecimal before,BigDecimal after,Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Reputation outbox requires business transaction"); }
        var d=decision.data(); UUID eventId=UUID.randomUUID();
        var payload=new Envelope(eventId,"REPUTATION_CHANGED",1,"user-service",now,decision.actorId(),List.of(d.accountId()),"ACCOUNT",d.accountId(),
                new Data(d.accountId(),d.contractId(),d.decisionId(),d.action().name(),after.subtract(before),before,after,d.reasonCode()));
        String encoded;
        try { encoded=json.writeValueAsString(payload); } catch (Exception ex) { throw new IllegalStateException("Reputation envelope encoding failed"); }
        jdbc.update("INSERT INTO outbox(event_id,aggregate_id,channel,event_type,topic,payload,created_at,available_at) VALUES (?,?,'KAFKA','REPUTATION_CHANGED',?,?::jsonb,?,?)",
                eventId,d.accountId(),TOPIC,encoded,Timestamp.from(now),Timestamp.from(now));
    }
}
