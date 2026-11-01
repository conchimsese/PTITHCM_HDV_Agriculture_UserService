package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import vn.agriculture.soa.user.account.*;
import vn.agriculture.soa.user.fixtures.ProfileReplica;
import vn.agriculture.soa.user.outbox.*;
import vn.agriculture.soa.user.profile.ProfileDtos.Snapshot;
import vn.agriculture.soa.user.profile.ProfileService;

@SpringBootTest(properties={"user.auth.enabled=false","user.outbox.kafka-enabled=false","user.outbox.email-enabled=false",
        "user.outbox.bootstrap-enabled=false","user.auth.mail-from=user-service@example.test","user.outbox.support-email=support@example.test","debug=false"})
class OutboxPostgresKafkaIT {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka-native:3.9.1");
    static final GenericContainer<?> MAIL=new GenericContainer<>("axllent/mailpit:v1.27.10").withExposedPorts(1025,8025)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        DB.start(); KAFKA.start(); MAIL.start();
        p.add("spring.datasource.url",DB::getJdbcUrl); p.add("spring.datasource.username",DB::getUsername); p.add("spring.datasource.password",DB::getPassword);
        p.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);
        p.add("spring.mail.host",MAIL::getHost); p.add("spring.mail.port",()->MAIL.getMappedPort(1025));
        p.add("spring.mail.properties.mail.smtp.auth",()->"false"); p.add("spring.mail.properties.mail.smtp.starttls.enable",()->"false");
        p.add("spring.mail.properties.mail.smtp.starttls.required",()->"false");
        p.add("spring.mail.properties.mail.smtp.connectiontimeout",()->"500");
        p.add("spring.mail.properties.mail.smtp.timeout",()->"500");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OutboxRelay relay;
    @Autowired ProfileOutboxWriter profiles;
    @Autowired AccountOutboxWriter accounts;
    @Autowired ProfileBootstrap bootstrap;
    @Autowired ProfileService profileService;
    @Autowired JavaMailSenderImpl sender;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean OutboxRepository repository;
    @BeforeEach void reset() throws Exception {
        jdbc.execute("TRUNCATE accounts CASCADE");
        sender.setPort(MAIL.getMappedPort(1025));
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(mailUri("/api/v1/messages")).DELETE().build(),HttpResponse.BodyHandlers.discarding());
        try (var admin=AdminClient.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))) {
            Set<String> existing=admin.listTopics().names().get(10,TimeUnit.SECONDS);
            for (String topic:List.of(ProfileOutboxWriter.TOPIC,AccountOutboxWriter.TOPIC)) {
                if (!existing.contains(topic)) { admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(10,TimeUnit.SECONDS); }
            }
        }
    }
    private UUID user(AccountRole role,AccountStatus status) {
        UUID id=UUID.randomUUID(); String name="outbox_"+id.toString().substring(0,8);
        jdbc.update("INSERT INTO accounts(account_id,username,email,password_hash,role,status,email_verified_at,reputation_score,admin_lock) VALUES (?,?,?,?,?,?,now(),?,?)",
                id,name,name+"@example.test","test-only-hash",role.name(),status.name(),role==AccountRole.ADMIN?null:10,status==AccountStatus.LOCKED);
        if (role==AccountRole.FARMER) { jdbc.update("INSERT INTO farmer_profiles(farmer_id,full_name) VALUES (?,?)",id,"Public Farmer"); }
        if (role==AccountRole.TRADER) { jdbc.update("INSERT INTO trader_profiles(trader_id,business_name) VALUES (?,?)",id,"Public Trader"); }
        return id;
    }
    private UUID profile(UUID id,long version,boolean visible) {
        return new TransactionTemplate(manager).execute(status->{
            profiles.append(id,AccountRole.FARMER,version,visible?new Snapshot("Public Farmer",null,"Dong Thap",null,Instant.parse("2026-10-01T00:00:00Z")):null,
                    Instant.parse("2026-11-01T00:00:00Z"),id);
            return jdbc.queryForObject("SELECT event_id FROM outbox WHERE aggregate_id=? ORDER BY delivery_sequence DESC LIMIT 1",UUID.class,id);
        });
    }
    private void accountLock(UUID id,boolean verified) {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.update("UPDATE accounts SET status='LOCKED',admin_lock=true,account_version=account_version+1 WHERE account_id=?",id);
            accounts.appendStatusChange(id,jdbc.queryForObject("SELECT email FROM accounts WHERE account_id=?",String.class,id),verified,AccountStatus.LOCKED,"Private admin evidence",1,Instant.now(),id);
        });
    }
    private String payload(UUID id) { return jdbc.queryForObject("SELECT payload::text FROM outbox WHERE event_id=?",String.class,id); }
    private boolean published(UUID id) { return jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox WHERE event_id=?",Boolean.class,id); }
    private void due() { jdbc.update("UPDATE outbox SET available_at=now()-interval '1 second' WHERE published_at IS NULL"); }
    private KafkaConsumer<String,String> consumer(String topic) {
        var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",KAFKA.getBootstrapServers(),"group.id","fixture-"+UUID.randomUUID(),
                "key.deserializer",StringDeserializer.class.getName(),"value.deserializer",StringDeserializer.class.getName(),"enable.auto.commit","false"));
        var partition=new TopicPartition(topic,0); consumer.assign(List.of(partition)); consumer.seekToBeginning(List.of(partition)); return consumer;
    }
    private List<ConsumerRecord<String,String>> records(KafkaConsumer<String,String> consumer,UUID id,int count) throws Exception {
        var results=new ArrayList<ConsumerRecord<String,String>>(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (results.size()<count && System.nanoTime()<deadline) {
            for (var record:consumer.poll(Duration.ofMillis(250))) {
                if (json.readTree(record.value()).get("eventId").asText().equals(id.toString())) { results.add(record); }
            }
        }
        assertThat(results.size()).isGreaterThanOrEqualTo(count); return results;
    }
    private URI mailUri(String path) { return URI.create("http://"+MAIL.getHost()+":"+MAIL.getMappedPort(8025)+path); }
    private JsonNode mailGet(String path) throws Exception {
        return json.readTree(HttpClient.newHttpClient().send(HttpRequest.newBuilder(mailUri(path)).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test void realBrokerReceivesExactEnvelopeAndAccountKeyBeforeDbIsMarked() throws Exception {
        UUID user=user(AccountRole.FARMER,AccountStatus.ACTIVE),event=profile(user,1,true); String original=payload(event);
        assertThat(published(event)).isFalse(); assertThat(relay.processOne("KAFKA")).isTrue(); assertThat(published(event)).isTrue();
        try (var consumer=consumer(ProfileOutboxWriter.TOPIC)) {
            var received=records(consumer,event,1).getFirst();
            assertThat(received.key()).isEqualTo(user.toString()); assertThat(received.value()).isEqualTo(original);
            assertThat(json.readTree(received.value()).get("data").get("profile").size()).isEqualTo(5);
        }
    }
    @Test void migrationBackfillsLegacyPendingRowsAndContinuesSequenceWithoutCollisions() {
        String schema="phase5_migration";
        try {
            org.flywaydb.core.Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
                    .schemas(schema).defaultSchema(schema).target("2").load().migrate();
            UUID account=UUID.randomUUID(),older=UUID.randomUUID(),newer=UUID.randomUUID();
            jdbc.update("INSERT INTO phase5_migration.accounts(account_id,username,email,password_hash,role) VALUES (?,'legacy_admin','legacy@example.test','test-only','ADMIN')",account);
            jdbc.update("INSERT INTO phase5_migration.outbox(event_id,aggregate_id,channel,event_type,topic,payload,created_at,available_at) VALUES (?,?,'KAFKA','USER_PROFILE_UPDATED','soa.user.profile.v1','{}','2026-10-02','2026-10-02')",newer,account);
            jdbc.update("INSERT INTO phase5_migration.outbox(event_id,aggregate_id,channel,event_type,topic,payload,created_at,available_at) VALUES (?,?,'KAFKA','USER_PROFILE_UPDATED','soa.user.profile.v1','{}','2026-10-01','2026-10-01')",older,account);
            org.flywaydb.core.Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForList("SELECT event_id FROM phase5_migration.outbox ORDER BY delivery_sequence",UUID.class)).containsExactly(older,newer);
            UUID last=UUID.randomUUID();
            jdbc.update("INSERT INTO phase5_migration.outbox(event_id,aggregate_id,channel,event_type,topic,payload) VALUES (?,?,'KAFKA','USER_PROFILE_UPDATED','soa.user.profile.v1','{}')",last,account);
            assertThat(jdbc.queryForObject("SELECT delivery_sequence FROM phase5_migration.outbox WHERE event_id=?",Long.class,last)).isEqualTo(3);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS phase5_migration CASCADE"); }
    }
    @Test void brokerDownRetainsEventAndPayloadAndRetriesAfterRecovery() throws Exception {
        UUID event=profile(user(AccountRole.FARMER,AccountStatus.ACTIVE),1,true); String original=payload(event);
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            assertThat(relay.processOne("KAFKA")).isTrue(); assertThat(published(event)).isFalse();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox WHERE event_id=?",Integer.class,event)).isEqualTo(1);
            assertThat(payload(event)).isEqualTo(original); assertThat(relay.processOne("KAFKA")).isFalse();
        } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        due(); assertThat(relay.processOne("KAFKA")).isTrue(); assertThat(published(event)).isTrue();
        try (var consumer=consumer(ProfileOutboxWriter.TOPIC)) { assertThat(records(consumer,event,1)).allSatisfy(r->assertThat(r.value()).isEqualTo(original)); }
    }
    @Test void crashAfterBrokerAckBeforeMarkerReplaysSameEventWithoutLoss() throws Exception {
        UUID id=user(AccountRole.FARMER,AccountStatus.ACTIVE),event=profile(id,1,true); String original=payload(event);
        doThrow(new DataAccessResourceFailureException("Injected post-ack database failure")).when(repository).published(eq(event),any());
        assertThatThrownBy(()->relay.processOne("KAFKA")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(published(event)).isFalse();
        doCallRealMethod().when(repository).published(eq(event),any());
        assertThat(relay.processOne("KAFKA")).isTrue();
        try (var consumer=consumer(ProfileOutboxWriter.TOPIC)) {
            var received=records(consumer,event,2); var replica=new ProfileReplica();
            for (var record:received) { assertThat(record.value()).isEqualTo(original); replica.apply(record.value()); }
            assertThat(replica.get(id).version()).isEqualTo(1); assertThat(replica.get(id).visible()).isTrue();
        }
    }
    @Test void sameTimestampEventsStayInInsertOrderAndFailedHeadBlocksOnlyItsAccount() throws Exception {
        UUID first=user(AccountRole.FARMER,AccountStatus.ACTIVE),second=user(AccountRole.FARMER,AccountStatus.ACTIVE);
        UUID a=profile(first,1,true),b=profile(first,2,false),c=profile(second,1,true);
        jdbc.update("UPDATE outbox SET available_at=now()+interval '1 hour' WHERE event_id=?",a);
        assertThat(relay.processOne("KAFKA")).isTrue(); assertThat(published(c)).isTrue(); assertThat(published(b)).isFalse();
        due(); relay.processOne("KAFKA"); assertThat(published(a)).isTrue(); assertThat(published(b)).isFalse();
        relay.processOne("KAFKA"); assertThat(published(b)).isTrue();
    }
    @Test void concurrentRelayWorkersCannotSendSameRowOrOvertakeAnAccountHead() throws Exception {
        UUID id=user(AccountRole.FARMER,AccountStatus.ACTIVE),first=profile(id,1,true),second=profile(id,2,false);
        var selected=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{
            selected.countDown(); if (!release.await(5,TimeUnit.SECONDS)) { throw new IllegalStateException("Test timeout"); }
            return call.callRealMethod();
        }).when(repository).published(eq(first),any());
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->relay.processOne("KAFKA")); assertThat(selected.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThat(relay.processOne("KAFKA")).isFalse(); assertThat(published(second)).isFalse(); }
            finally { release.countDown(); }
            assertThat(a.get(10,TimeUnit.SECONDS)).isTrue();
        }
        relay.processOne("KAFKA"); assertThat(published(second)).isTrue();
    }
    @Test void smtpFailureDoesNotRollbackLockOrBlockKafkaAndMailRecoversSeparately() throws Exception {
        UUID id=user(AccountRole.FARMER,AccountStatus.ACTIVE); accountLock(id,true);
        UUID kafka=jdbc.queryForObject("SELECT event_id FROM outbox WHERE channel='KAFKA'",UUID.class);
        UUID email=jdbc.queryForObject("SELECT event_id FROM outbox WHERE channel='EMAIL'",UUID.class);
        sender.setPort(1);
        try {
            assertThat(relay.processOne("EMAIL")).isTrue(); assertThat(published(email)).isFalse();
            assertThat(relay.processOne("KAFKA")).isTrue(); assertThat(published(kafka)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?",String.class,id)).isEqualTo("LOCKED");
        } finally { sender.setPort(MAIL.getMappedPort(1025)); }
        due(); assertThat(relay.processOne("EMAIL")).isTrue(); assertThat(published(email)).isTrue();
        var messages=mailGet("/api/v1/messages").get("messages"); assertThat(messages.size()).isEqualTo(1);
        String text=mailGet("/api/v1/message/"+messages.get(0).get("ID").asText()).get("Text").asText();
        assertThat(text).contains("support@example.test").doesNotContain("Private admin evidence","otp","password");
        try (var consumer=consumer(AccountOutboxWriter.TOPIC)) {
            var received=records(consumer,kafka,1).getFirst(); var data=json.readTree(received.value()).get("data");
            assertThat(data.size()).isEqualTo(3); assertThat(data.get("reasonCode").asText()).isEqualTo("ADMIN_LOCK");
            assertThat(received.value()).doesNotContain(jdbc.queryForObject("SELECT email FROM accounts WHERE account_id=?",String.class,id),"Private admin evidence");
        }
    }
    @Test void unverifiedAccountProducesKafkaButNoEmailIntent() {
        UUID id=user(AccountRole.FARMER,AccountStatus.ACTIVE); accountLock(id,false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE channel='EMAIL'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE channel='KAFKA'",Integer.class)).isEqualTo(1);
    }
    @Test void unlockEmailAndKafkaEnvelopeUseTheSameAccountVersion() throws Exception {
        UUID id=user(AccountRole.TRADER,AccountStatus.LOCKED);
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.update("UPDATE accounts SET status='ACTIVE',admin_lock=false,account_version=2 WHERE account_id=?",id);
            accounts.appendStatusChange(id,jdbc.queryForObject("SELECT email FROM accounts WHERE account_id=?",String.class,id),true,AccountStatus.ACTIVE,"Do not forward",2,Instant.now(),id);
        });
        relay.processOne("EMAIL"); relay.processOne("KAFKA");
        UUID event=jdbc.queryForObject("SELECT event_id FROM outbox WHERE channel='KAFKA'",UUID.class);
        try (var consumer=consumer(AccountOutboxWriter.TOPIC)) {
            var message=json.readTree(records(consumer,event,1).getFirst().value());
            assertThat(message.get("eventType").asText()).isEqualTo("ACCOUNT_UNLOCKED");
            assertThat(message.get("data").get("accountVersion").asLong()).isEqualTo(2);
            assertThat(message.get("data").get("reasonCode").asText()).isEqualTo("ADMIN_UNLOCK");
        }
        var messages=mailGet("/api/v1/messages").get("messages"); assertThat(messages.size()).isEqualTo(1);
        var delivered=mailGet("/api/v1/message/"+messages.get(0).get("ID").asText());
        assertThat(delivered.get("Text").asText()).contains("mở khóa","đăng nhập lại").doesNotContain("Do not forward");
    }
    @Test void bootstrapSnapshotsAllNonAdminStatesAndIsSafeToRerun() throws Exception {
        UUID active=user(AccountRole.FARMER,AccountStatus.ACTIVE),hidden=user(AccountRole.TRADER,AccountStatus.LOCKED);
        user(AccountRole.ADMIN,AccountStatus.ACTIVE);
        bootstrap.snapshots(); bootstrap.snapshots();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT profile_version FROM accounts WHERE account_id=?",Long.class,active)).isEqualTo(1);
        var replica=new ProfileReplica();
        for (String payload:jdbc.queryForList("SELECT payload::text FROM outbox ORDER BY delivery_sequence",String.class)) { replica.apply(payload); }
        assertThat(replica.get(active).visible()).isTrue(); assertThat(replica.get(hidden).visible()).isFalse();
        assertThat(replica.get(hidden).profile().isNull()).isTrue();
        profileService.put(active,json.readTree("{\"fullName\":\"Updated\"}")); bootstrap.snapshots();
        for (String payload:jdbc.queryForList("SELECT payload::text FROM outbox ORDER BY delivery_sequence",String.class)) { replica.apply(payload); }
        assertThat(replica.get(active).version()).isEqualTo(2); assertThat(replica.get(active).profile().get("displayName").asText()).isEqualTo("Updated");
    }
    @Test void legacyIntentForUnverifiedAddressIsNeverDelivered() throws Exception {
        UUID id=user(AccountRole.FARMER,AccountStatus.ACTIVE); accountLock(id,true);
        jdbc.update("UPDATE accounts SET email_verified_at=NULL WHERE account_id=?",id);
        assertThat(relay.processOne("EMAIL")).isTrue();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox WHERE channel='EMAIL'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox WHERE channel='EMAIL'",Integer.class)).isEqualTo(1);
        assertThat(mailGet("/api/v1/messages").get("messages").size()).isZero();
    }
    @Test void consumerFixtureIgnoresDuplicateAndStaleSnapshotAfterHide() throws Exception {
        UUID id=user(AccountRole.FARMER,AccountStatus.ACTIVE),show=profile(id,1,true),hide=profile(id,2,false);
        var replica=new ProfileReplica(); replica.apply(payload(show)); replica.apply(payload(hide));
        replica.apply(payload(show)); replica.apply(payload(hide));
        assertThat(replica.get(id).version()).isEqualTo(2); assertThat(replica.get(id).visible()).isFalse();
        assertThat(replica.get(id).profile().isNull()).isTrue();
    }
    @Test void emailCrashBeforeMarkerAllowsDuplicateButKeepsIntentRecoverable() throws Exception {
        accountLock(user(AccountRole.FARMER,AccountStatus.ACTIVE),true);
        UUID email=jdbc.queryForObject("SELECT event_id FROM outbox WHERE channel='EMAIL'",UUID.class);
        doThrow(new DataAccessResourceFailureException("Injected post-SMTP failure")).when(repository).published(eq(email),any());
        assertThatThrownBy(()->relay.processOne("EMAIL")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(published(email)).isFalse(); doCallRealMethod().when(repository).published(eq(email),any());
        relay.processOne("EMAIL"); assertThat(published(email)).isTrue();
        assertThat(mailGet("/api/v1/messages").get("messages").size()).isEqualTo(2);
    }
}
