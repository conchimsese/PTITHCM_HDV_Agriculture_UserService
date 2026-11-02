package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.agriculture.soa.user.account.*;
import vn.agriculture.soa.user.auth.*;
import vn.agriculture.soa.user.fixtures.DecisionEvents;
import vn.agriculture.soa.user.reputation.*;

@SpringBootTest(properties={"user.auth.enabled=true","user.auth.seed-enabled=false","debug=false","user.media.cleanup-cron=-"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(ReputationPostgresIT.TestTime.class)
class ReputationPostgresIT {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    static final Path KEYS=keys();
    static Path keys() {
        try {
            Path dir=Files.createTempDirectory("user-reputation-it-"); var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
            Files.writeString(dir.resolve("private.pem"),Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            Files.writeString(dir.resolve("public.pem"),Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())); return dir;
        } catch (Exception ex) { throw new IllegalStateException("Test key setup failed",ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        DB.start(); p.add("spring.datasource.url",DB::getJdbcUrl); p.add("spring.datasource.username",DB::getUsername); p.add("spring.datasource.password",DB::getPassword);
        p.add("user.auth.otp-hmac-secret",()->"test-only-reputation-secret-at-least-32-bytes");
        p.add("user.auth.private-key-path",()->KEYS.resolve("private.pem").toString()); p.add("user.auth.public-key-path",()->KEYS.resolve("public.pem").toString());
        p.add("user.auth.key-id",()->"reputation-test");
    }
    static class TestClock extends Clock {
        Instant now=Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @TestConfiguration static class TestTime { @Bean @Primary TestClock clock() { return new TestClock(); } }
    @Autowired JdbcTemplate jdbc;
    @Autowired ReputationService reputation;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;
    @Autowired JwtTokens tokens;
    @Autowired vn.agriculture.soa.user.admin.AdminAccountService admin;
    @MockitoBean OtpMailer mail;
    UUID farmer,trader;
    @BeforeEach void reset() {
        // Keep real JWT library time validation valid; individual cap tests advance this clock explicitly.
        jdbc.execute("TRUNCATE accounts CASCADE"); clock.now=Instant.now();
        farmer=user(AccountRole.FARMER); trader=user(AccountRole.TRADER);
    }
    UUID user(AccountRole role) {
        UUID id=UUID.randomUUID(); String name="rep_"+id.toString().substring(0,8);
        jdbc.update("INSERT INTO accounts(account_id,username,email,password_hash,role,status,email_verified_at,reputation_score) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                id,name,name+"@example.test","test-only-hash",role.name(),java.sql.Timestamp.from(clock.instant()),role==AccountRole.ADMIN?null:10);
        if (role==AccountRole.FARMER) { jdbc.update("INSERT INTO farmer_profiles(farmer_id,full_name) VALUES (?,?)",id,"Reputation Farmer"); }
        if (role==AccountRole.TRADER) { jdbc.update("INSERT INTO trader_profiles(trader_id,business_name) VALUES (?,?)",id,"Reputation Trader"); }
        return id;
    }
    boolean apply(ObjectNode event) { return reputation.apply(event.get("data").get("accountId").asText(),event.toString()); }
    BigDecimal score(UUID id) { return jdbc.queryForObject("SELECT reputation_score FROM accounts WHERE account_id=?",BigDecimal.class,id); }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    ObjectNode penalty(String code,int delta) { return DecisionEvents.penalty(farmer,trader,code,delta); }
    ObjectNode reward(UUID contract) { return DecisionEvents.event(farmer,trader,contract,UUID.randomUUID(),"COMPLETION_REWARD",null,new BigDecimal("0.5")); }

    @Test void penaltyRecordsRequestedActualAndEmitsOnlyContractWhitelist() throws Exception {
        var event=penalty("F04",-3); assertThat(apply(event)).isTrue(); assertThat(score(farmer)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT requested_delta FROM reputation_history",BigDecimal.class)).isEqualByComparingTo("-3");
        assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history",BigDecimal.class)).isEqualByComparingTo("-3");
        String payload=jdbc.queryForObject("SELECT payload::text FROM outbox",String.class);
        var emitted=new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        assertThat(emitted.get("eventType").asText()).isEqualTo("REPUTATION_CHANGED"); assertThat(emitted.get("data").size()).isEqualTo(8);
        assertThat(emitted.get("referenceId").asText()).isEqualTo(farmer.toString());
        assertThat(emitted.get("recipientIds").get(0).asText()).isEqualTo(farmer.toString());
        assertThat(payload).doesNotContain("email","phone","password","token","otp");
    }
    @Test void replaySameAndNewEventIdNeverAppliesBusinessDecisionTwice() {
        var event=penalty("F01",-1); assertThat(apply(event)).isTrue(); assertThat(apply(event)).isFalse();
        event.put("eventId",UUID.randomUUID().toString()); assertThat(apply(event)).isFalse();
        assertThat(score(farmer)).isEqualByComparingTo("9"); assertThat(count("reputation_history")).isEqualTo(1);
        assertThat(count("reputation_event_receipts")).isEqualTo(2); assertThat(count("outbox")).isEqualTo(1);
    }
    @Test void changedPayloadUnderExistingEventOrBusinessKeyIsAConflict() {
        var event=penalty("F01",-1); apply(event);
        ((ObjectNode)event.get("data")).put("reasonCode","CHANGED");
        assertThatThrownBy(()->apply(event)).isInstanceOf(DecisionFailure.class).hasMessage("EVENT_ID_CONFLICT");
        event.put("eventId",UUID.randomUUID().toString());
        assertThatThrownBy(()->apply(event)).isInstanceOf(DecisionFailure.class).hasMessage("DECISION_BUSINESS_KEY_CONFLICT");
        assertThat(score(farmer)).isEqualByComparingTo("9"); assertThat(count("reputation_history")).isEqualTo(1);
    }
    @Test void concurrentDuplicateAndDistinctDecisionsAreSerialized() throws Exception {
        var first=penalty("F01",-1); var duplicate=first.deepCopy(); duplicate.put("eventId",UUID.randomUUID().toString());
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return apply(first);}); var b=pool.submit(()->{gate.await();return apply(duplicate);}); gate.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(score(farmer)).isEqualByComparingTo("9");
        var second=penalty("F02",-2); var third=penalty("F03",-2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->apply(second)); var b=pool.submit(()->apply(third));
            assertThat(a.get(10,TimeUnit.SECONDS)).isTrue(); assertThat(b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(score(farmer)).isEqualByComparingTo("5"); assertThat(count("reputation_history")).isEqualTo(3);
    }
    @Test void zeroScoreClampsRevokesRefreshAndBlocksRealOldJwt() throws Exception {
        jdbc.update("UPDATE accounts SET reputation_score=1 WHERE account_id=?",farmer);
        String jwt=tokens.issue(farmer,AccountRole.FARMER);
        assertThat(mvc.perform(get("/api/v1/users/me").servletPath("/api/v1/users/me").header("Authorization","Bearer "+jwt)).andReturn().getResponse().getStatus()).isEqualTo(200);
        jdbc.update("INSERT INTO refresh_tokens(refresh_token_id,account_id,token_hash,expires_at) VALUES (?,?,?,?)",UUID.randomUUID(),farmer,"a".repeat(64),java.sql.Timestamp.from(clock.instant().plusSeconds(86400)));
        apply(penalty("F08",-5)); assertThat(score(farmer)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history",BigDecimal.class)).isEqualByComparingTo("-1");
        assertThat(jdbc.queryForObject("SELECT reputation_lock FROM accounts WHERE account_id=?",Boolean.class,farmer)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?",String.class,farmer)).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",Integer.class)).isZero();
        assertThat(mvc.perform(get("/api/v1/users/me").servletPath("/api/v1/users/me").header("Authorization","Bearer "+jwt)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(count("outbox")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT payload->'data'->>'reasonCode' FROM outbox WHERE channel='KAFKA' AND event_type='ACCOUNT_LOCKED'",String.class)).isEqualTo("REPUTATION_ZERO");
    }
    @Test void inactiveAndAdminLockRemainIndependentAtScoreZero() {
        jdbc.update("UPDATE accounts SET reputation_score=1,status='INACTIVE',admin_lock=true WHERE account_id=?",farmer);
        apply(penalty("F02",-2));
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?",String.class,farmer)).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT admin_lock AND reputation_lock FROM accounts WHERE account_id=?",Boolean.class,farmer)).isTrue();
        assertThat(count("outbox")).isEqualTo(1);
    }
    @Test void rewardCapUsesPositiveActualAwardsAndCapHitIsNeverAppliedLater() {
        jdbc.update("UPDATE accounts SET reputation_score=8 WHERE account_id=?",farmer);
        apply(reward(UUID.randomUUID())); apply(reward(UUID.randomUUID())); var capped=reward(UUID.randomUUID()); apply(capped);
        assertThat(score(farmer)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history WHERE actual_delta=0",Integer.class)).isEqualTo(1);
        clock.now=clock.now.plus(Duration.ofDays(31)); capped.put("eventId",UUID.randomUUID().toString()); assertThat(apply(capped)).isFalse();
        assertThat(score(farmer)).isEqualByComparingTo("9"); apply(reward(UUID.randomUUID())); assertThat(score(farmer)).isEqualByComparingTo("9.5");
    }
    @Test void rewardAtScoreCeilingStoresActualZeroAndBusinessKeyCannotBeReused() {
        UUID contract=UUID.randomUUID(); var event=reward(contract); apply(event);
        assertThat(score(farmer)).isEqualByComparingTo("10"); assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history",BigDecimal.class)).isEqualByComparingTo("0");
        assertThatThrownBy(()->apply(reward(contract))).isInstanceOf(DecisionFailure.class).hasMessage("REWARD_BUSINESS_KEY_CONFLICT");
        assertThat(count("reputation_history")).isEqualTo(1);
    }
    @Test void latePenaltyCannotPassAnUnreversedRewardForTheSameContract() {
        UUID contract=UUID.randomUUID(); apply(reward(contract));
        var late=DecisionEvents.event(farmer,trader,contract,UUID.randomUUID(),"PENALTY","F01",BigDecimal.valueOf(-1));
        assertThatThrownBy(()->apply(late)).isInstanceOfSatisfying(DecisionFailure.class,ex->{assertThat(ex.retryable()).isTrue(); assertThat(ex.getMessage()).isEqualTo("CONTRACT_DECISION_ORDER_UNRESOLVED");});
        assertThat(count("reputation_history")).isEqualTo(1); assertThat(score(farmer)).isEqualByComparingTo("10");
    }
    @Test void outboxFailureRollsBackScoreHistoryFlagsRevocationAndReceipt() {
        jdbc.update("UPDATE accounts SET reputation_score=1 WHERE account_id=?",farmer);
        jdbc.update("INSERT INTO refresh_tokens(refresh_token_id,account_id,token_hash,expires_at) VALUES (?,?,?,?)",UUID.randomUUID(),farmer,"b".repeat(64),java.sql.Timestamp.from(clock.instant().plusSeconds(86400)));
        var event=penalty("F01",-1); jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase6_failure CHECK (false) NOT VALID");
        try { assertThatThrownBy(()->apply(event)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase6_failure"); }
        assertThat(score(farmer)).isEqualByComparingTo("1"); assertThat(count("reputation_history")).isZero(); assertThat(count("reputation_event_receipts")).isZero();
        assertThat(count("outbox")).isZero(); assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?",String.class,farmer)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",Integer.class)).isEqualTo(1);
        assertThat(apply(event)).isTrue();
    }
    @Test void rejectsAdminAndSameRoleCounterpartyWithoutSideEffects() {
        UUID admin=user(AccountRole.ADMIN);
        assertThatThrownBy(()->apply(DecisionEvents.penalty(admin,trader,"F01",-1))).isInstanceOf(DecisionFailure.class);
        UUID anotherFarmer=user(AccountRole.FARMER);
        assertThatThrownBy(()->apply(DecisionEvents.penalty(farmer,anotherFarmer,"F01",-1))).isInstanceOf(DecisionFailure.class).hasMessage("COUNTERPARTY_ROLE_INVALID");
        assertThat(count("reputation_history")).isZero(); assertThat(count("outbox")).isZero();
    }
    @Test void eventIdCannotBeReusedForAnotherAccountAndReplayJobIsIdempotent() throws Exception {
        var first=penalty("F01",-1); apply(first);
        UUID other=user(AccountRole.FARMER); var second=DecisionEvents.penalty(other,trader,"F01",-1);
        second.put("eventId",first.get("eventId").asText());
        assertThatThrownBy(()->apply(second)).isInstanceOf(DecisionFailure.class).hasMessage("EVENT_ID_CONFLICT");
        assertThat(score(other)).isEqualByComparingTo("10");
        Path replay=Files.createTempFile("user-reputation-replay-",".jsonl");
        Files.writeString(replay,first.toString()+System.lineSeparator());
        var operator=new ReputationReplay(reputation,new ReputationDecision.Parser(new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())),false,"");
        operator.replay(replay); assertThat(score(farmer)).isEqualByComparingTo("9"); assertThat(count("reputation_history")).isEqualTo(1);
    }
    ObjectNode reversal(ObjectNode base,BigDecimal requested) {
        var d=base.get("data");
        var event=DecisionEvents.event(UUID.fromString(d.get("accountId").asText()),UUID.fromString(d.get("counterpartyId").asText()),
                UUID.fromString(d.get("contractId").asText()),UUID.randomUUID(),"REVERSAL",null,requested);
        ((ObjectNode)event.get("data")).put("originalDecisionId",d.get("decisionId").asText()).put("decisionVersion",2);
        return event;
    }
    @Test void missingOriginalRetriesWithoutAnyMutation() {
        var event=DecisionEvents.event(farmer,trader,UUID.randomUUID(),UUID.randomUUID(),"REVERSAL",null,BigDecimal.ONE);
        ((ObjectNode)event.get("data")).put("originalDecisionId",UUID.randomUUID().toString()).put("decisionVersion",2);
        assertThatThrownBy(()->apply(event)).isInstanceOfSatisfying(DecisionFailure.class,ex->{
            assertThat(ex.retryable()).isTrue(); assertThat(ex.getMessage()).isEqualTo("ORIGINAL_DECISION_NOT_FOUND");
        });
        assertThat(count("reputation_history")).isZero(); assertThat(count("reputation_event_receipts")).isZero(); assertThat(count("outbox")).isZero();
        assertThat(score(farmer)).isEqualByComparingTo("10");
    }
    @Test void reversalUsesActualClampedPenaltyAndWaitsForAdminEvenWithOldJwt() throws Exception {
        jdbc.update("UPDATE accounts SET reputation_score=1 WHERE account_id=?",farmer);
        String jwt=tokens.issue(farmer,AccountRole.FARMER);
        var base=penalty("F08",-5); apply(base); var reverse=reversal(base,BigDecimal.valueOf(5));
        assertThat(apply(reverse)).isTrue(); assertThat(score(farmer)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history WHERE action='REVERSAL'",BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT requested_delta FROM reputation_history WHERE action='REVERSAL'",BigDecimal.class)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT (payload->'data'->>'delta')::numeric FROM outbox WHERE event_type='REPUTATION_CHANGED' AND payload->'data'->>'action'='REVERSAL'",BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT reputation_lock FROM accounts WHERE account_id=?",Boolean.class,farmer)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?",String.class,farmer)).isEqualTo("LOCKED");
        assertThat(mvc.perform(get("/api/v1/users/me").servletPath("/api/v1/users/me").header("Authorization","Bearer "+jwt)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type='ACCOUNT_UNLOCKED'",Integer.class)).isZero();
        assertThat(apply(reverse)).isFalse(); reverse.put("eventId",UUID.randomUUID().toString()); assertThat(apply(reverse)).isFalse();
        assertThat(count("reputation_history")).isEqualTo(2);
        assertThatThrownBy(()->apply(reversal(base,BigDecimal.valueOf(5)))).hasMessage("ORIGINAL_ALREADY_REVERSED");
        admin.updateStatus(user(AccountRole.ADMIN),farmer,AccountStatus.ACTIVE,"REVIEWED_REVERSAL");
        assertThat(mvc.perform(get("/api/v1/users/me").servletPath("/api/v1/users/me").header("Authorization","Bearer "+jwt)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
    @Test void rewardReversalUsesOnlyGrantedActualAndDoesNotRestoreQuotaOrReward() {
        jdbc.update("UPDATE accounts SET reputation_score=9.5 WHERE account_id=?",farmer);
        var base=reward(UUID.randomUUID()); apply(base); apply(reversal(base,BigDecimal.valueOf(-5)));
        assertThat(score(farmer)).isEqualByComparingTo("9.5");
        apply(reward(UUID.randomUUID())); var capped=reward(UUID.randomUUID()); apply(capped);
        assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history WHERE decision_id=?",BigDecimal.class,UUID.fromString(capped.get("data").get("decisionId").asText()))).isEqualByComparingTo("0");
        assertThat(apply(base)).isFalse(); assertThat(score(farmer)).isEqualByComparingTo("10");
        assertThatThrownBy(()->apply(reward(UUID.fromString(base.get("data").get("contractId").asText())))).hasMessage("REWARD_BUSINESS_KEY_CONFLICT");
    }
    @Test void reversalOfZeroActualDoesNotInventPointsAndReversalCannotBeReversed() {
        var base=reward(UUID.randomUUID()); apply(base); var reverse=reversal(base,BigDecimal.valueOf(-1)); apply(reverse);
        assertThat(score(farmer)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history WHERE action='REVERSAL'",BigDecimal.class)).isEqualByComparingTo("0");
        assertThatThrownBy(()->apply(reversal(reverse,BigDecimal.ONE))).hasMessage("REVERSAL_OF_REVERSAL_NOT_SUPPORTED");
    }
    @Test void reversalClampsAgainstCurrentScoreAndKeepsInactiveAdminAndVerificationGuards() {
        jdbc.update("UPDATE accounts SET reputation_score=1,status='INACTIVE',admin_lock=true,email_verified_at=NULL WHERE account_id=?",farmer);
        var base=penalty("F08",-5); apply(base); apply(reversal(base,BigDecimal.TEN));
        assertThat(score(farmer)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT admin_lock AND NOT reputation_lock AND email_verified_at IS NULL AND status='INACTIVE' FROM accounts WHERE account_id=?",Boolean.class,farmer)).isTrue();
        jdbc.update("UPDATE accounts SET reputation_score=9.5 WHERE account_id=?",farmer);
        var next=penalty("F01",-1); apply(next);
        apply(reward(UUID.randomUUID())); apply(reward(UUID.randomUUID()));
        apply(reversal(next,BigDecimal.TEN)); assertThat(score(farmer)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history WHERE action='REVERSAL' AND original_decision_id=?",BigDecimal.class,UUID.fromString(next.get("data").get("decisionId").asText()))).isEqualByComparingTo("0.5");
    }
    @Test void rejectsReversalScopeMismatchAndLegacyRewardReversalVersion() {
        var base=penalty("F01",-1); apply(base);
        for (String field:List.of("contractId","counterpartyId")) {
            var reverse=reversal(base,BigDecimal.ONE);
            ((ObjectNode)reverse.get("data")).put(field,field.equals("counterpartyId")?user(AccountRole.TRADER).toString():UUID.randomUUID().toString());
            if (field.equals("contractId")) { reverse.put("referenceId",reverse.get("data").get(field).asText()); }
            assertThatThrownBy(()->apply(reverse)).hasMessage("REVERSAL_ORIGINAL_MISMATCH");
        }
        var reverse=reversal(base,BigDecimal.ONE); ((ObjectNode)reverse.get("data")).put("decisionVersion",1);
        assertThatThrownBy(()->apply(reverse)).hasMessage("INVALID_DECISION_VERSION");
        assertThat(count("reputation_history")).isEqualTo(1);
    }
    @Test void reversalRollbackAndConcurrentDoubleReversalAreAtomic() throws Exception {
        jdbc.update("UPDATE accounts SET reputation_score=1 WHERE account_id=?",farmer);
        var base=penalty("F08",-5); apply(base); var reverse=reversal(base,BigDecimal.ONE);
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase6_reversal_failure CHECK(false) NOT VALID");
        try { assertThatThrownBy(()->apply(reverse)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase6_reversal_failure"); }
        assertThat(score(farmer)).isEqualByComparingTo("0"); assertThat(count("reputation_history")).isEqualTo(1); assertThat(count("reputation_event_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reputation_lock AND status='LOCKED' FROM accounts WHERE account_id=?",Boolean.class,farmer)).isTrue();
        var second=reversal(base,BigDecimal.ONE); var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var jobs=List.of(reverse,second).stream().map(e->pool.submit(()->{start.await(); try { return apply(e)?1:0; } catch(DecisionFailure ex) { assertThat(ex.getMessage()).isEqualTo("ORIGINAL_ALREADY_REVERSED"); return 0; }})).toList();
            start.countDown(); assertThat(jobs.get(0).get(10,TimeUnit.SECONDS)+jobs.get(1).get(10,TimeUnit.SECONDS)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(score(farmer)).isEqualByComparingTo("1"); assertThat(count("reputation_history")).isEqualTo(2);
    }
    @Test void orderedRewardReversalPenaltyAndPenaltyReversalRewardAreSupported() {
        jdbc.update("UPDATE accounts SET reputation_score=8 WHERE account_id=?",farmer);
        UUID contract=UUID.randomUUID(); var earned=reward(contract); apply(earned); apply(reversal(earned,BigDecimal.valueOf(-0.5)));
        var fine=DecisionEvents.event(farmer,trader,contract,UUID.randomUUID(),"PENALTY","F01",BigDecimal.valueOf(-1)); apply(fine);
        assertThat(score(farmer)).isEqualByComparingTo("7");
        UUID another=UUID.randomUUID(); var otherFine=DecisionEvents.event(farmer,trader,another,UUID.randomUUID(),"PENALTY","F01",BigDecimal.valueOf(-1));
        apply(otherFine); apply(reversal(otherFine,BigDecimal.ONE)); apply(reward(another));
        assertThat(score(farmer)).isEqualByComparingTo("7.5");
    }
    @Test void rewardReversalAtFloorCannotCreateNegativeScoreOrClearZeroLock() {
        jdbc.update("UPDATE accounts SET reputation_score=0.5 WHERE account_id=?",farmer);
        var base=reward(UUID.randomUUID()); apply(base); apply(penalty("F08",-5)); apply(reversal(base,BigDecimal.valueOf(-10)));
        assertThat(score(farmer)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT actual_delta FROM reputation_history WHERE action='REVERSAL'",BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT reputation_lock AND status='LOCKED' FROM accounts WHERE account_id=?",Boolean.class,farmer)).isTrue();
    }
    @Test void reversalMayReuseOriginalIdWithItsOwnActionAndVersionButCannotChangePayload() {
        var base=penalty("F01",-1); apply(base); var reverse=reversal(base,BigDecimal.ONE);
        ((ObjectNode)reverse.get("data")).put("decisionId",base.get("data").get("decisionId").asText());
        assertThat(apply(reverse)).isTrue(); reverse.put("eventId",UUID.randomUUID().toString()); assertThat(apply(reverse)).isFalse();
        ((ObjectNode)reverse.get("data")).put("reasonCode","CHANGED"); reverse.put("eventId",UUID.randomUUID().toString());
        assertThatThrownBy(()->apply(reverse)).hasMessage("DECISION_BUSINESS_KEY_CONFLICT");
        assertThat(score(farmer)).isEqualByComparingTo("10");
    }
    @Test void originalCanBeReplayedBeforeRetryingAnEarlyReversal() {
        var base=penalty("F01",-1); var reverse=reversal(base,BigDecimal.TEN);
        assertThatThrownBy(()->apply(reverse)).hasMessage("ORIGINAL_DECISION_NOT_FOUND");
        assertThat(count("reputation_event_receipts")).isZero();
        apply(base); assertThat(apply(reverse)).isTrue();
        assertThat(score(farmer)).isEqualByComparingTo("10"); assertThat(count("reputation_history")).isEqualTo(2);
    }
}
