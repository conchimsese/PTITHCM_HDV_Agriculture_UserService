package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import vn.agriculture.soa.user.fixtures.DecisionEvents;
import vn.agriculture.soa.user.reputation.*;

@SpringBootTest(properties={"user.auth.enabled=false","user.reputation.enabled=true","user.reputation.retry-ms=50","user.reputation.invalid-retry-ms=50","debug=false"})
class ReputationKafkaIT {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka-native:3.9.1");
    static final TopicPartition SOURCE=new TopicPartition(ReputationConsumer.TOPIC,0);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        DB.start(); KAFKA.start();
        try (var admin=AdminClient.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))) {
            Set<String> existing=admin.listTopics().names().get(10,TimeUnit.SECONDS);
            for (String topic:List.of(ReputationConsumer.TOPIC,DecisionDeadLetterPublisher.TOPIC)) {
                if (!existing.contains(topic)) { admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(10,TimeUnit.SECONDS); }
            }
        }
        p.add("spring.datasource.url",DB::getJdbcUrl); p.add("spring.datasource.username",DB::getUsername); p.add("spring.datasource.password",DB::getPassword);
        p.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String,String> producer;
    @Autowired KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean ReputationService service;
    @MockitoSpyBean DecisionDeadLetterPublisher dlt;
    UUID account,counterparty;
    AdminClient admin;
    @BeforeEach void reset() throws Exception {
        registry.getListenerContainer("user-reputation-decisions").stop();
        admin=AdminClient.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()));
        jdbc.execute("TRUNCATE accounts CASCADE"); account=user("FARMER"); counterparty=user("TRADER");
        long end=admin.listOffsets(Map.of(SOURCE,OffsetSpec.latest())).all().get(10,TimeUnit.SECONDS).get(SOURCE).offset();
        admin.alterConsumerGroupOffsets(ReputationConsumer.GROUP,Map.of(SOURCE,new OffsetAndMetadata(end))).all().get(10,TimeUnit.SECONDS);
        registry.getListenerContainer("user-reputation-decisions").start();
        await(()->{var parts=registry.getListenerContainer("user-reputation-decisions").getAssignedPartitions();return parts!=null && !parts.isEmpty();});
    }
    @AfterEach void stop() {
        registry.getListenerContainer("user-reputation-decisions").stop();
        if (admin!=null) { admin.close(Duration.ofSeconds(2)); }
    }
    UUID user(String role) {
        UUID id=UUID.randomUUID(); String name="rep_kafka_"+id.toString().substring(0,8);
        jdbc.update("INSERT INTO accounts(account_id,username,email,password_hash,role,status,email_verified_at,reputation_score) VALUES (?,?,?,?,?,'ACTIVE',now(),10)",id,name,name+"@example.test","test-only",role);
        if (role.equals("FARMER")) { jdbc.update("INSERT INTO farmer_profiles(farmer_id,full_name) VALUES (?,?)",id,"Kafka Farmer"); }
        else { jdbc.update("INSERT INTO trader_profiles(trader_id,business_name) VALUES (?,?)",id,"Kafka Trader"); }
        return id;
    }
    long send(String payload) throws Exception {
        return producer.send(ReputationConsumer.TOPIC,account.toString(),payload).get(10,TimeUnit.SECONDS).getRecordMetadata().offset();
    }
    long committed() {
        try {
            var offset=admin.listConsumerGroupOffsets(ReputationConsumer.GROUP).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(SOURCE);
            return offset==null?-1:offset.offset();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime()<end) { if (condition.getAsBoolean()) { return; } Thread.sleep(25); }
        throw new AssertionError("Kafka condition timed out");
    }
    ObjectNode penalty() { return DecisionEvents.penalty(account,counterparty,"F01",-1); }

    @Test void committedBusinessBeforeAckAndCrashWindowReplaysWithoutAnotherScoreChange() throws Exception {
        String payload=penalty().toString(); var first=new AtomicBoolean(true); var afterCommit=new CountDownLatch(1);
        doAnswer(call->{
            Object applied=call.callRealMethod();
            if (first.compareAndSet(true,false)) { afterCommit.countDown(); throw new IllegalStateException("Injected crash before offset ACK"); }
            return applied;
        }).when(service).apply(eq(account.toString()),eq(payload));
        long offset=send(payload); assertThat(afterCommit.await(10,TimeUnit.SECONDS)).isTrue();
        await(()->committed()>offset);
        assertThat(jdbc.queryForObject("SELECT reputation_score FROM accounts WHERE account_id=?",BigDecimal.class,account)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_event_receipts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class)).isEqualTo(1);
        verify(service,atLeast(2)).apply(account.toString(),payload);
    }
    @Test void failedDltForwardNeverCommitsSourceOffsetAndRecoveryPreservesPayload() throws Exception {
        String payload="{invalid-"+UUID.randomUUID(); var allow=new AtomicBoolean(false); var failed=new CountDownLatch(1);
        doAnswer(call->{
            if (!allow.get()) { failed.countDown(); throw new IllegalStateException("Injected DLT outage"); }
            call.callRealMethod(); return null;
        }).when(dlt).send(argThat(record->payload.equals(record.value())),any(Exception.class));
        long offset=send(payload); assertThat(failed.await(10,TimeUnit.SECONDS)).isTrue(); assertThat(committed()).isLessThanOrEqualTo(offset);
        allow.set(true); await(()->committed()>offset);
        try (var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",KAFKA.getBootstrapServers(),"group.id","dlt-fixture-"+UUID.randomUUID(),
                "key.deserializer",StringDeserializer.class.getName(),"value.deserializer",StringDeserializer.class.getName(),"enable.auto.commit","false"))) {
            var partition=new TopicPartition(DecisionDeadLetterPublisher.TOPIC,0); consumer.assign(List.of(partition)); consumer.seekToBeginning(List.of(partition));
            var found=new ArrayList<ConsumerRecord<String,String>>();
            await(()->{for (var record:consumer.poll(Duration.ofMillis(100))) {if (payload.equals(record.value())) {found.add(record);}} return !found.isEmpty();});
            var record=found.getFirst(); assertThat(record.key()).isEqualTo(account.toString());
            assertThat(new String(record.headers().lastHeader("user-error-code").value(),java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("INVALID_DECISION_ENVELOPE");
            assertThat(record.headers().lastHeader("user-original-offset")).isNotNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history",Integer.class)).isZero();
    }
    @Test void databaseFailureRetriesWithoutAckOrPartialStateAndRecovers() throws Exception {
        String payload=penalty().toString(); var attempted=new CountDownLatch(1);
        doAnswer(call->{attempted.countDown();return call.callRealMethod();}).when(service).apply(eq(account.toString()),eq(payload));
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase6_kafka_failure CHECK (false) NOT VALID");
        long offset;
        try {
            offset=send(payload); assertThat(attempted.await(10,TimeUnit.SECONDS)).isTrue(); assertThat(committed()).isLessThanOrEqualTo(offset);
            assertThat(jdbc.queryForObject("SELECT reputation_score FROM accounts WHERE account_id=?",BigDecimal.class,account)).isEqualByComparingTo("10");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history",Integer.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase6_kafka_failure"); }
        await(()->committed()>offset);
        assertThat(jdbc.queryForObject("SELECT reputation_score FROM accounts WHERE account_id=?",BigDecimal.class,account)).isEqualByComparingTo("9");
    }
    @Test void missingOriginalReversalDoesNotAckApplyOrForwardToDlt() throws Exception {
        var reversal=DecisionEvents.event(account,counterparty,UUID.randomUUID(),UUID.randomUUID(),"REVERSAL",null,BigDecimal.ONE);
        ((ObjectNode)reversal.get("data")).put("originalDecisionId",UUID.randomUUID().toString()).put("decisionVersion",2);
        String payload=reversal.toString(); var seen=new CountDownLatch(1);
        doAnswer(call->{seen.countDown();return call.callRealMethod();}).when(service).apply(eq(account.toString()),eq(payload));
        long offset=send(payload); assertThat(seen.await(10,TimeUnit.SECONDS)).isTrue(); Thread.sleep(200);
        assertThat(committed()).isLessThanOrEqualTo(offset);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT reputation_score FROM accounts WHERE account_id=?",BigDecimal.class,account)).isEqualByComparingTo("10");
        verify(dlt,never()).send(argThat(record->payload.equals(record.value())),any(Exception.class));
    }
    @Test void reversalCommitsBeforeAckAndDuplicateNeverRestoresTwice() throws Exception {
        var base=penalty(); service.apply(account.toString(),base.toString());
        var reversal=DecisionEvents.event(account,counterparty,UUID.fromString(base.get("data").get("contractId").asText()),UUID.randomUUID(),"REVERSAL",null,BigDecimal.TEN);
        ((ObjectNode)reversal.get("data")).put("originalDecisionId",base.get("data").get("decisionId").asText()).put("decisionVersion",2);
        String payload=reversal.toString(); var first=new AtomicBoolean(true);
        doAnswer(call->{Object result=call.callRealMethod(); if (first.compareAndSet(true,false)) { throw new IllegalStateException("Crash after reversal commit"); } return result;})
                .when(service).apply(eq(account.toString()),eq(payload));
        long offset=send(payload); await(()->committed()>offset);
        assertThat(jdbc.queryForObject("SELECT reputation_score FROM accounts WHERE account_id=?",BigDecimal.class,account)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reputation_history",Integer.class)).isEqualTo(2);
        verify(service,atLeast(2)).apply(account.toString(),payload);
    }
}
