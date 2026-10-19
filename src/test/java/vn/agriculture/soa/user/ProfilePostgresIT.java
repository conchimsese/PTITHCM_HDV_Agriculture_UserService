package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.auth.JwtTokens;
import vn.agriculture.soa.user.auth.OtpMailer;
import vn.agriculture.soa.user.outbox.ProfileOutboxWriter;

@SpringBootTest(properties={"user.auth.enabled=true","user.auth.seed-enabled=false","debug=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ProfilePostgresIT {
    private static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Path KEYS=keys();
    private static Path keys() {
        try {
            Path dir=Files.createTempDirectory("user-profile-it-");
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
            Files.writeString(dir.resolve("private.pem"),Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            Files.writeString(dir.resolve("public.pem"),Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            return dir;
        } catch (Exception ex) { throw new IllegalStateException("Test key setup failed"); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        DB.start();
        properties.add("spring.datasource.url",DB::getJdbcUrl);
        properties.add("spring.datasource.username",DB::getUsername);
        properties.add("spring.datasource.password",DB::getPassword);
        properties.add("user.auth.otp-hmac-secret",()->"test-only-profile-otp-secret-at-least-32-bytes");
        properties.add("user.auth.private-key-path",()->KEYS.resolve("private.pem").toString());
        properties.add("user.auth.public-key-path",()->KEYS.resolve("public.pem").toString());
        properties.add("user.auth.key-id",()->"profile-test-current");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokens tokens;
    @MockitoBean OtpMailer mail;

    private record User(UUID id,String access,AccountRole role) {
        @Override public String toString() { return "TestUser["+id+","+role+"]"; }
    }
    private record Reply(int status,JsonNode body,String cache) { }
    @BeforeEach void reset() { jdbc.execute("TRUNCATE accounts CASCADE"); }
    private User user(AccountRole role) {
        UUID id=UUID.randomUUID(); String name="profile_"+id.toString().substring(0,8);
        jdbc.update("""
                INSERT INTO accounts(account_id,username,email,password_hash,role,status,email_verified_at,reputation_score)
                VALUES (?,?,?,?,?,'ACTIVE',now(),?)
                """,id,name,name+"@example.test","test-only-fixture-hash",role.name(),role==AccountRole.ADMIN?null:10);
        if (role==AccountRole.FARMER) {
            jdbc.update("INSERT INTO farmer_profiles(farmer_id,full_name,phone,address,province,district) VALUES (?,?,?,?,?,?)",
                    id,"Original Farmer","0900000000","Private farmer address","Dong Thap","Cao Lanh");
        } else if (role==AccountRole.TRADER) {
            jdbc.update("INSERT INTO trader_profiles(trader_id,business_name,phone,address,province,district,buying_area) VALUES (?,?,?,?,?,?,?)",
                    id,"Original Trader","0911111111","Private trader address","Can Tho","Ninh Kieu","Private buying area");
        }
        return new User(id,tokens.issue(id,role),role);
    }
    private Reply request(MockHttpServletRequestBuilder request) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();
        JsonNode body=response.getContentAsByteArray().length==0?json.nullNode():json.readTree(response.getContentAsByteArray());
        return new Reply(response.getStatus(),body,response.getHeader("Cache-Control"));
    }
    private Reply read(String path,User caller) throws Exception {
        return request(get(path).servletPath(path).header("Authorization","Bearer "+caller.access()));
    }
    private Reply putProfile(User caller,Object body) throws Exception {
        return request(put("/api/v1/users/me").servletPath("/api/v1/users/me").header("Authorization","Bearer "+caller.access())
                .contentType("application/json").content(json.writeValueAsBytes(body)));
    }
    private Set<String> fields(JsonNode node) {
        Set<String> fields=new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields;
    }
    private long version(User user) { return jdbc.queryForObject("SELECT profile_version FROM accounts WHERE account_id=?",Long.class,user.id()); }
    private int events(User user) { return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=?",Integer.class,user.id()); }
    private JsonNode event(User user) throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=? ORDER BY (payload->'data'->>'profileVersion')::bigint DESC LIMIT 1",String.class,user.id()));
    }
    private void publicWhitelist(Reply result,User target,String displayName) {
        assertThat(result.status()).isEqualTo(200);
        assertThat(fields(result.body())).containsExactlyInAnyOrder("accountId","displayName","avatarUrl","province","district","joinedAt");
        assertThat(result.body().get("accountId").asText()).isEqualTo(target.id().toString());
        assertThat(result.body().get("displayName").asText()).isEqualTo(displayName);
        assertThat(result.cache()).contains("private","no-store");
        assertThat(result.body().toString()).doesNotContain("Private farmer address","Private trader address","0900000000","0911111111","Private buying area");
    }

    @Test void farmerMeReturnsOnlyOwnPrivateDtoWithoutSecrets() throws Exception {
        var farmer=user(AccountRole.FARMER); var other=user(AccountRole.FARMER);
        var result=read("/api/v1/users/me",farmer);
        assertThat(result.status()).isEqualTo(200);
        assertThat(fields(result.body())).containsExactlyInAnyOrder("accountId","username","email","role","status","createdAt","emailVerifiedAt","profile");
        assertThat(result.body().get("accountId").asText()).isEqualTo(farmer.id().toString());
        assertThat(result.body().get("profile").get("phone").asText()).isEqualTo("0900000000");
        assertThat(fields(result.body().get("profile"))).containsExactlyInAnyOrder("fullName","phone","address","province","district","avatarUrl");
        assertThat(result.body().toString()).doesNotContain("password","token","reputation","profileVersion","test-only-fixture-hash");
        var spoof=request(get("/api/v1/users/me").servletPath("/api/v1/users/me").param("accountId",other.id().toString())
                .header("X-User-Id",other.id().toString()).header("X-User-Role","ADMIN").header("Authorization","Bearer "+farmer.access()));
        assertThat(spoof.body().get("accountId").asText()).isEqualTo(farmer.id().toString());
        assertThat(result.cache()).contains("private","no-store");
    }
    @Test void traderMeHasRoleSpecificPrivateFields() throws Exception {
        var trader=user(AccountRole.TRADER); var result=read("/api/v1/users/me",trader);
        assertThat(result.status()).isEqualTo(200);
        assertThat(fields(result.body().get("profile"))).containsExactlyInAnyOrder("businessName","phone","address","province","district","avatarUrl","buyingArea");
        assertThat(result.body().get("profile").get("buyingArea").asText()).isEqualTo("Private buying area");
    }
    @Test void adminMeHasNullProfileAndCannotPutProfile() throws Exception {
        var admin=user(AccountRole.ADMIN);
        var read=read("/api/v1/users/me",admin); assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().get("profile").isNull()).isTrue();
        var result=putProfile(admin,Map.of("fullName","Not a Farmer"));
        assertThat(result.status()).isEqualTo(400); assertThat(result.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM farmer_profiles",Integer.class)).isZero();
        assertThat(events(admin)).isZero();
    }
    @Test void publicEndpointsUseExactWhitelistForFarmerAndTraderAndAdminCaller() throws Exception {
        var farmer=user(AccountRole.FARMER); var trader=user(AccountRole.TRADER); var admin=user(AccountRole.ADMIN);
        publicWhitelist(read("/api/v1/users/"+farmer.id(),trader),farmer,"Original Farmer");
        publicWhitelist(read("/api/v1/farmers/"+farmer.id(),admin),farmer,"Original Farmer");
        publicWhitelist(read("/api/v1/users/"+trader.id(),farmer),trader,"Original Trader");
        var result=read("/api/v1/traders/"+trader.id(),admin);
        publicWhitelist(result,trader,"Original Trader");
        String joined=jdbc.queryForObject("SELECT created_at FROM accounts WHERE account_id=?",java.sql.Timestamp.class,trader.id()).toInstant().toString();
        assertThat(result.body().get("joinedAt").asText()).isEqualTo(joined);
    }
    @Test void nonActiveWrongRoleAdminAndMissingTargetsAre404() throws Exception {
        var caller=user(AccountRole.FARMER); var farmer=user(AccountRole.FARMER); var trader=user(AccountRole.TRADER); var admin=user(AccountRole.ADMIN);
        for (String path:List.of("/api/v1/users/"+admin.id(),"/api/v1/farmers/"+trader.id(),"/api/v1/traders/"+farmer.id(),"/api/v1/users/"+UUID.randomUUID())) {
            assertThat(read(path,caller).status()).isEqualTo(404);
        }
        for (String state:List.of("LOCKED","INACTIVE","UNVERIFIED")) {
            jdbc.update("UPDATE accounts SET status=?,admin_lock=? WHERE account_id=?",state,state.equals("LOCKED"),farmer.id());
            assertThat(read("/api/v1/users/"+farmer.id(),caller).status()).isEqualTo(404);
            assertThat(read("/api/v1/farmers/"+farmer.id(),caller).status()).isEqualTo(404);
        }
    }
    @Test void allFiveEndpointsRequireBearerAndIgnoreServiceOrIdentityHeaders() throws Exception {
        var farmer=user(AccountRole.FARMER);
        for (String path:List.of("/api/v1/users/me","/api/v1/users/"+farmer.id(),"/api/v1/farmers/"+farmer.id(),"/api/v1/traders/"+farmer.id())) {
            assertThat(request(get(path).servletPath(path).header("X-Service-Key","fake-service-key").header("X-User-Role","ADMIN")).status()).isEqualTo(401);
        }
        assertThat(request(put("/api/v1/users/me").servletPath("/api/v1/users/me").contentType("application/json").content("{\"fullName\":\"No bearer\"}")).status()).isEqualTo(401);
    }
    @Test void lockedCallerOldJwtCannotReadOrWriteProfiles() throws Exception {
        var caller=user(AccountRole.FARMER); var target=user(AccountRole.TRADER);
        jdbc.update("UPDATE accounts SET status='LOCKED',admin_lock=true WHERE account_id=?",caller.id());
        for (String path:List.of("/api/v1/users/me","/api/v1/users/"+target.id(),"/api/v1/traders/"+target.id())) {
            var result=read(path,caller); assertThat(result.status()).isEqualTo(403);
            assertThat(result.body().get("code").asText()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        }
        assertThat(putProfile(caller,Map.of("fullName","Forbidden change")).status()).isEqualTo(403);
        assertThat(version(caller)).isZero(); assertThat(events(caller)).isZero();
    }
    @Test void putFarmerReplacesOptionalFieldsAndWritesWhitelistedVersionedOutbox() throws Exception {
        var farmer=user(AccountRole.FARMER);
        var result=putProfile(farmer,Map.of("fullName","  Updated Farmer  "));
        assertThat(result.status()).isEqualTo(200);
        var profile=result.body().get("profile"); assertThat(profile.get("fullName").asText()).isEqualTo("Updated Farmer");
        for (String key:List.of("phone","address","province","district","avatarUrl")) { assertThat(profile.get(key).isNull()).isTrue(); }
        assertThat(version(farmer)).isEqualTo(1); assertThat(events(farmer)).isEqualTo(1);
        var event=event(farmer);
        assertThat(event.get("eventType").asText()).isEqualTo("USER_PROFILE_UPDATED");
        assertThat(event.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(event.get("sourceService").asText()).isEqualTo("user-service");
        assertThat(event.get("referenceType").asText()).isEqualTo("ACCOUNT");
        assertThat(event.get("referenceId").asText()).isEqualTo(farmer.id().toString());
        assertThat(event.get("actorId").asText()).isEqualTo(farmer.id().toString());
        assertThat(event.get("recipientIds").isEmpty()).isTrue();
        assertThat(fields(event.get("data"))).containsExactlyInAnyOrder("accountId","role","profileVersion","publicVisible","profile");
        assertThat(event.get("data").get("profileVersion").asLong()).isEqualTo(1);
        assertThat(event.get("data").get("publicVisible").asBoolean()).isTrue();
        assertThat(fields(event.get("data").get("profile"))).containsExactlyInAnyOrder("displayName","avatarUrl","province","district","joinedAt");
        assertThat(event.toString()).doesNotContain("email","phone","address","buyingArea","reputation","password","token","status");
        var stored=jdbc.queryForMap("SELECT * FROM outbox WHERE aggregate_id=?",farmer.id());
        assertThat(stored.get("aggregate_id")).isEqualTo(farmer.id());
        assertThat(stored.get("event_id").toString()).isEqualTo(event.get("eventId").asText());
        assertThat(stored.get("topic")).isEqualTo("soa.user.profile.v1");
        assertThat(stored.get("published_at")).isNull();
    }
    @Test void putTraderReturnsPrivateFieldsButEventNeverContainsThem() throws Exception {
        var trader=user(AccountRole.TRADER);
        var result=putProfile(trader,Map.of("businessName","Updated Trader","phone","0999999999","address","New secret address","buyingArea","New secret area"));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("profile").get("buyingArea").asText()).isEqualTo("New secret area");
        assertThat(event(trader).toString()).doesNotContain("0999999999","New secret address","New secret area","buyingArea");
    }
    @Test void repeatedPutIsIdempotentAndPrivateOnlyChangesDoNotPublish() throws Exception {
        var farmer=user(AccountRole.FARMER);
        var body=Map.of("fullName","Original Farmer","phone","0999999999","province","Dong Thap","district","Cao Lanh");
        assertThat(putProfile(farmer,body).status()).isEqualTo(200);
        assertThat(version(farmer)).isZero(); assertThat(events(farmer)).isZero();
        var changed=Map.of("fullName","Changed Farmer");
        assertThat(putProfile(farmer,changed).status()).isEqualTo(200);
        assertThat(putProfile(farmer,changed).status()).isEqualTo(200);
        assertThat(version(farmer)).isEqualTo(1); assertThat(events(farmer)).isEqualTo(1);
    }
    @Test void unknownImmutableAndCrossRoleFieldsAreRejectedEvenIfNull() throws Exception {
        var farmer=user(AccountRole.FARMER);
        for (String field:List.of("accountId","username","email","password","role","status","reputation","reputationScore","profileVersion","profile","full_name","businessName","buyingArea")) {
            var body=new HashMap<String,Object>(); body.put("fullName","Must not change"); body.put(field,null);
            var result=putProfile(farmer,body); assertThat(result.status()).as("rejected field %s",field).isEqualTo(400);
            assertThat(result.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        var trader=user(AccountRole.TRADER);
        assertThat(putProfile(trader,Map.of("businessName","Trader","fullName","Wrong role")).status()).isEqualTo(400);
        assertThat(version(farmer)).isZero(); assertThat(events(farmer)).isZero();
    }
    @Test void requiredNameTypesLengthsAndJsonShapeAreValidated() throws Exception {
        var farmer=user(AccountRole.FARMER);
        for (Object body:List.of(Map.of(),Map.of("fullName","   "),Map.of("fullName",123),Map.of("fullName","x".repeat(151)),
                Map.of("fullName","Name","phone","x".repeat(21)),Map.of("fullName","Name","province","x".repeat(101)),
                Map.of("fullName","Name","address",true),List.of("not-an-object"),"not-an-object")) {
            assertThat(putProfile(farmer,body).status()).isEqualTo(400);
        }
        assertThat(putProfile(farmer,null).status()).isEqualTo(400);
        assertThat(events(farmer)).isZero();
    }
    @Test void actorOnlyUpdatesOwnProfileAndCannotUsePatch() throws Exception {
        var caller=user(AccountRole.FARMER); var other=user(AccountRole.FARMER);
        assertThat(putProfile(caller,Map.of("fullName","Caller changed")).status()).isEqualTo(200);
        assertThat(read("/api/v1/users/"+other.id(),caller).body().get("displayName").asText()).isEqualTo("Original Farmer");
        assertThat(version(other)).isZero(); assertThat(events(other)).isZero();
        assertThat(request(patch("/api/v1/users/me").servletPath("/api/v1/users/me").header("Authorization","Bearer "+caller.access())
                .contentType("application/json").content("{\"fullName\":\"Patch disallowed\"}")).status()).isEqualTo(403);
    }
    private UUID media(User owner,UUID resource,boolean attached) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO media_assets(media_id,owner_account_id,resource_id,storage_key,content_type,size_bytes,sha256,attached_at) VALUES (?,?,?,?,'image/png',100,?,?)",
                id,owner.id(),resource,UUID.randomUUID(),"a".repeat(64),attached?java.sql.Timestamp.from(Instant.now()):null);
        return id;
    }
    @Test void avatarRequiresCanonicalUrlAndAttachedMetadataOwnedByCaller() throws Exception {
        var farmer=user(AccountRole.FARMER); var other=user(AccountRole.FARMER);
        UUID foreign=media(other,other.id(),true),temporary=media(farmer,farmer.id(),false),wrongResource=media(farmer,other.id(),true);
        for (String avatar:List.of("https://example.test/avatar.png","file:///private/avatar.png","/api/v1/users/media/../../etc/passwd",
                "/api/v1/users/media/"+foreign,"/api/v1/users/media/"+temporary,"/api/v1/users/media/"+wrongResource,
                "/api/v1/users/media/"+UUID.randomUUID(),"/api/v1/users/media/"+foreign+"?token=unsafe")) {
            assertThat(putProfile(farmer,Map.of("fullName","Name","avatarUrl",avatar)).status()).isEqualTo(400);
        }
        assertThat(version(farmer)).isZero(); assertThat(events(farmer)).isZero();
        UUID own=media(farmer,farmer.id(),true); String avatar="/api/v1/users/media/"+own;
        assertThat(putProfile(farmer,Map.of("fullName","Name","avatarUrl",avatar)).status()).isEqualTo(200);
        assertThat(read("/api/v1/farmers/"+farmer.id(),other).body().get("avatarUrl").asText()).isEqualTo(avatar);
    }
    @Test void outboxFailureRollsBackProfileAndVersionAndReturnsNoSuccess() throws Exception {
        var farmer=user(AccountRole.FARMER);
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase2_fail_outbox CHECK (false) NOT VALID");
        try {
            var failed=putProfile(farmer,Map.of("fullName","Must rollback"));
            assertThat(failed.status()).isEqualTo(503);
            assertThat(failed.body().get("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
            assertThat(version(farmer)).isZero(); assertThat(events(farmer)).isZero();
            assertThat(jdbc.queryForObject("SELECT full_name FROM farmer_profiles WHERE farmer_id=?",String.class,farmer.id())).isEqualTo("Original Farmer");
        } finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase2_fail_outbox"); }
    }
    private List<Reply> race(java.util.concurrent.Callable<Reply> first,java.util.concurrent.Callable<Reply> second) throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return first.call();}); var b=pool.submit(()->{gate.await();return second.call();});
            gate.countDown(); return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
    }
    @Test void concurrentDistinctPutsProduceOrderedVersionsAndConsistentSnapshots() throws Exception {
        var farmer=user(AccountRole.FARMER);
        var results=race(()->putProfile(farmer,Map.of("fullName","Name A")),()->putProfile(farmer,Map.of("fullName","Name B")));
        assertThat(results).allSatisfy(result->assertThat(result.status()).isEqualTo(200));
        assertThat(version(farmer)).isEqualTo(2); assertThat(events(farmer)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT (payload->'data'->>'profileVersion')::bigint FROM outbox WHERE aggregate_id=? ORDER BY 1",Long.class,farmer.id()))
                .containsExactly(1L,2L);
        assertThat(event(farmer).get("data").get("profile").get("displayName").asText())
                .isEqualTo(read("/api/v1/users/"+farmer.id(),farmer).body().get("displayName").asText());
    }
    @Test void concurrentIdenticalPutsCommitOnePublicChange() throws Exception {
        var farmer=user(AccountRole.FARMER); var body=Map.of("fullName","Identical change");
        assertThat(race(()->putProfile(farmer,body),()->putProfile(farmer,body)))
                .allSatisfy(result->assertThat(result.status()).isEqualTo(200));
        assertThat(version(farmer)).isEqualTo(1); assertThat(events(farmer)).isEqualTo(1);
    }
    @Test void malformedTargetUuidReturns400AfterAuthentication() throws Exception {
        var caller=user(AccountRole.FARMER);
        var result=read("/api/v1/users/not-a-uuid",caller);
        assertThat(result.status()).isEqualTo(400); assertThat(result.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
    @Test void writerCannotAppendOutsideBusinessTransaction() {
        var writer=new ProfileOutboxWriter(jdbc,json);
        assertThatThrownBy(()->writer.append(UUID.randomUUID(),AccountRole.FARMER,1,null,Instant.now(),null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Profile outbox requires the business transaction");
    }
}
