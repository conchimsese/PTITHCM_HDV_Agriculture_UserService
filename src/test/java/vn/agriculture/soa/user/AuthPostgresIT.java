package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.agriculture.soa.user.auth.*;
import vn.agriculture.soa.user.account.AccountRole;

@SpringBootTest(properties={"user.auth.enabled=true","user.auth.seed-enabled=false","debug=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(AuthPostgresIT.TestTime.class)
class AuthPostgresIT {
    private static final String PASSWORD="TestPassword9!";
    private static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Path KEYS=keys();
    private static Path keys() {
        try {
            Path dir=Files.createTempDirectory("user-auth-it-");
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
        properties.add("user.auth.otp-hmac-secret",()->"test-only-otp-secret-with-at-least-32-bytes");
        properties.add("user.auth.private-key-path",()->KEYS.resolve("private.pem").toString());
        properties.add("user.auth.public-key-path",()->KEYS.resolve("public.pem").toString());
        properties.add("user.auth.key-id",()->"test-current");
    }
    static class MutableClock extends Clock {
        private volatile Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long seconds) { now=now.plusSeconds(seconds); }
    }
    @TestConfiguration static class TestTime {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired AuthService service;
    @Autowired AuthMaintenance maintenance;
    @Autowired JwtTokens jwt;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;
    @Autowired AuthProperties settings;
    @MockitoBean OtpMailer mail;
    final Map<String,String> mailbox=new ConcurrentHashMap<>();
    final AtomicBoolean smtpWorks=new AtomicBoolean(true);

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE accounts CASCADE"); jdbc.execute("TRUNCATE login_attempt_windows");
        clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        mailbox.clear(); smtpWorks.set(true);
        when(mail.send(anyString(),anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String email=invocation.getArgument(0),code=invocation.getArgument(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email=?",Integer.class,email)).isEqualTo(1);
            if (smtpWorks.get()) { mailbox.put(email,code); }
            return smtpWorks.get();
        });
    }
    record Reply(int status,Map<String,Object> body,String retry) { }
    private Reply post(String path,Object data) throws Exception { return post(path,data,null); }
    private Reply post(String path,Object data,String access) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).servletPath(path)
                .contentType("application/json").content(json.writeValueAsBytes(data));
        if (access!=null) { request.header("Authorization","Bearer "+access); }
        var response=mvc.perform(request).andReturn().getResponse();
        Map<String,Object> body=response.getContentAsByteArray().length==0?Map.of():json.readValue(response.getContentAsByteArray(),Map.class);
        return new Reply(response.getStatus(),body,response.getHeader("Retry-After"));
    }
    private Map<String,Object> registerBody(String username,String email,String role) {
        return Map.of("username",username,"email",email,"password",PASSWORD,"role",role,
                "profile",role.equals("TRADER")?Map.of("businessName","Test Trader"):Map.of("fullName","Test Farmer"));
    }
    private Map<String,Object> register(String name) throws Exception {
        var result=post("/api/v1/auth/register",registerBody(name,name+"@example.test","FARMER"));
        assertThat(result.status()).isEqualTo(201); return result.body();
    }
    private Map<String,Object> active(String name) throws Exception {
        var account=register(name);
        assertThat(verify(name+"@example.test",mailbox.get(name+"@example.test")).status()).isEqualTo(200);
        return account;
    }
    private Reply verify(String email,String code) throws Exception { return post("/api/v1/auth/verify-email",Map.of("email",email,"otp",code)); }
    private Map<String,Object> login(String name) throws Exception {
        var result=post("/api/v1/auth/login",Map.of("username",name,"password",PASSWORD));
        assertThat(result.status()).isEqualTo(200); return result.body();
    }
    private Reply refresh(String token) throws Exception { return post("/api/v1/auth/refresh",Map.of("refreshToken",token)); }
    @Test void verificationWritesOnlyPublicSnapshotAndOutboxFailureRollsBackOtpAndActivation() throws Exception {
        var registered=register("verify-outbox"); UUID id=UUID.fromString((String)registered.get("accountId"));
        String code=mailbox.get("verify-outbox@example.test");
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase5_verify_failure CHECK (false) NOT VALID");
        try {
            assertThat(verify("verify-outbox@example.test",code).status()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?",String.class,id)).isEqualTo("UNVERIFIED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_verification_otps WHERE account_id=? AND used_at IS NOT NULL",Integer.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT profile_version FROM accounts WHERE account_id=?",Long.class,id)).isZero();
        } finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase5_verify_failure"); }
        assertThat(verify("verify-outbox@example.test",code).status()).isEqualTo(200);
        var event=new ObjectMapper().readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=?",String.class,id));
        assertThat(event.get("data").get("profileVersion").asLong()).isEqualTo(1);
        assertThat(event.get("data").get("publicVisible").asBoolean()).isTrue();
        assertThat(event.get("data").get("profile").size()).isEqualTo(5);
        assertThat(event.toString()).doesNotContain("email","phone","password","otp","token");
    }
    private List<Integer> race(Callable<Integer> first,Callable<Integer> second) throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{ gate.await(); return first.call(); });
            var b=pool.submit(()->{ gate.await(); return second.call(); });
            gate.countDown();
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    @Test void registrationCreatesMatchingProfileAndHashedOtpBeforeSmtp() throws Exception {
        var result=post("/api/v1/auth/register",registerBody(" Farmer "," Farmer@Example.Test ","FARMER"));
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).containsEntry("username","farmer").containsEntry("email","farmer@example.test")
                .containsEntry("status","UNVERIFIED").containsEntry("verificationEmailStatus","SENT");
        var stored=jdbc.queryForMap("SELECT * FROM accounts WHERE username='farmer'");
        assertThat(passwords.matches(PASSWORD,(String)stored.get("password_hash"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM farmer_profiles WHERE farmer_id=?",Integer.class,stored.get("account_id"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT code_hash FROM email_verification_otps WHERE account_id=?",String.class,stored.get("account_id")))
                .hasSize(64).isNotEqualTo(mailbox.get("farmer@example.test"));
        assertThat(post("/api/v1/auth/login",Map.of("username","farmer","password",PASSWORD)).status()).isEqualTo(403);
    }
    @Test void traderNeedsOnlyEmailVerification() throws Exception {
        var result=post("/api/v1/auth/register",registerBody("trader","trader@example.test","TRADER"));
        assertThat(result.status()).isEqualTo(201);
        assertThat(verify("trader@example.test",mailbox.get("trader@example.test")).status()).isEqualTo(200);
        assertThat(login("trader")).containsEntry("role","TRADER");
    }
    @Test void adminRoleAndWeakOrOversizedPasswordsAndUnexpectedFieldsAreRejected() throws Exception {
        assertThat(post("/api/v1/auth/register",registerBody("admin","admin@example.test","ADMIN")).status()).isEqualTo(400);
        for (String password:List.of("weak","A1!"+"á".repeat(35))) {
            var body=new HashMap<>(registerBody("farmer","farmer@example.test","FARMER")); body.put("password",password);
            assertThat(post("/api/v1/auth/register",body).status()).isEqualTo(400);
        }
        var unknown=new HashMap<>(registerBody("farmer","farmer@example.test","FARMER")); unknown.put("status","ACTIVE");
        assertThat(post("/api/v1/auth/register",unknown).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts",Integer.class)).isZero();
    }
    @Test void normalizedDuplicatesReturn409WithoutPartialProfile() throws Exception {
        register("farmer");
        var duplicate=post("/api/v1/auth/register",registerBody(" FARMER ","other@example.test","FARMER"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body()).containsEntry("code","ACCOUNT_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM farmer_profiles",Integer.class)).isEqualTo(1);
    }
    @Test void smtpFailureDoesNotRollbackAndResendFailureIs503() throws Exception {
        smtpWorks.set(false);
        assertThat(register("farmer")).containsEntry("verificationEmailStatus","FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE username='farmer'",String.class)).isEqualTo("UNVERIFIED");
        clock.advance(61);
        assertThat(post("/api/v1/auth/resend-verification",Map.of("email","farmer@example.test")).status()).isEqualTo(503);
        assertThat(post("/api/v1/auth/resend-verification",Map.of("email","absent@example.test")).status()).isEqualTo(202);
    }
    @Test void fiveWrongOtpAttemptsPersistAndCorrectCodeCannotBypassThem() throws Exception {
        register("farmer"); String correct=mailbox.get("farmer@example.test");
        String wrong=correct.equals("000000")?"000001":"000000";
        for (int i=0;i<5;i++) { assertThat(verify("farmer@example.test",wrong).status()).isEqualTo(400); }
        assertThat(verify("farmer@example.test",correct).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM email_verification_otps",Integer.class)).isEqualTo(5);
    }
    @Test void expiredAndUsedOtpCannotBeReused() throws Exception {
        register("expired"); clock.advance(601);
        assertThat(verify("expired@example.test",mailbox.get("expired@example.test")).status()).isEqualTo(400);
        active("verified");
        assertThat(verify("verified@example.test",mailbox.get("verified@example.test")).status()).isEqualTo(400);
    }
    @Test void otpCannotReactivateLockedOrInactiveAccounts() throws Exception {
        register("locked"); register("disabled");
        jdbc.update("UPDATE accounts SET status='LOCKED',admin_lock=true WHERE username='locked'");
        jdbc.update("UPDATE accounts SET status='INACTIVE' WHERE username='disabled'");
        assertThat(verify("locked@example.test",mailbox.get("locked@example.test")).status()).isEqualTo(400);
        assertThat(verify("disabled@example.test",mailbox.get("disabled@example.test")).status()).isEqualTo(400);
    }
    @Test void resendCooldownAndRollingQuotaSurviveOtpCleanup() throws Exception {
        register("farmer");
        var limited=post("/api/v1/auth/resend-verification",Map.of("email","farmer@example.test"));
        assertThat(limited.status()).isEqualTo(429); assertThat(limited.retry()).isEqualTo("60");
        for (int i=0;i<4;i++) {
            clock.advance(601); maintenance.cleanup();
            assertThat(post("/api/v1/auth/resend-verification",Map.of("email","farmer@example.test")).status()).isEqualTo(202);
        }
        clock.advance(61); maintenance.cleanup();
        assertThat(post("/api/v1/auth/resend-verification",Map.of("email","farmer@example.test")).status()).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_issuances",Integer.class)).isEqualTo(5);
    }
    @Test void concurrentVerifyHasOneWinner() throws Exception {
        register("farmer"); String code=mailbox.get("farmer@example.test");
        assertThat(race(()->verify("farmer@example.test",code).status(),()->verify("farmer@example.test",code).status()))
                .containsExactlyInAnyOrder(200,400);
    }
    @Test void concurrentResendHasOneWinnerAndInvalidatesOldCode() throws Exception {
        register("farmer"); String old=mailbox.get("farmer@example.test"); clock.advance(61);
        assertThat(race(()->post("/api/v1/auth/resend-verification",Map.of("email","farmer@example.test")).status(),
                ()->post("/api/v1/auth/resend-verification",Map.of("email","farmer@example.test")).status()))
                .containsExactlyInAnyOrder(202,429);
        String newer=mailbox.get("farmer@example.test");
        assertThat(newer).isNotEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_verification_otps WHERE invalidated_at IS NOT NULL",Integer.class)).isEqualTo(1);
        assertThat(verify("farmer@example.test",old).status()).isEqualTo(400);
        assertThat(verify("farmer@example.test",newer).status()).isEqualTo(200);
    }
    @Test void verifyVersusResendCannotLeaveActiveAccountWithReusableOtp() throws Exception {
        register("farmer"); String old=mailbox.get("farmer@example.test"); clock.advance(61);
        var results=race(()->verify("farmer@example.test",old).status(),
                ()->post("/api/v1/auth/resend-verification",Map.of("email","farmer@example.test")).status());
        assertThat(results.get(1)).isEqualTo(202);
        if (results.getFirst()==200) {
            assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE username='farmer'",String.class)).isEqualTo("ACTIVE");
            assertThat(verify("farmer@example.test",old).status()).isEqualTo(400);
        } else {
            assertThat(results.getFirst()).isEqualTo(400);
            assertThat(verify("farmer@example.test",mailbox.get("farmer@example.test")).status()).isEqualTo(200);
        }
    }
    @Test void loginJwtAndRefreshRotationAndLogoutWork() throws Exception {
        active("farmer"); var pair=login("farmer"); String access=(String)pair.get("accessToken"),refresh=(String)pair.get("refreshToken");
        var decoded=jwt.decoder().decode(access);
        assertThat(decoded.getHeaders()).containsEntry("alg","RS256").containsEntry("kid","test-current");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("urn:soa:user-service");
        assertThat(decoded.getAudience()).contains("soa-api");
        assertThat(Duration.between(decoded.getIssuedAt(),decoded.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(decoded.getClaims()).doesNotContainKeys("email","phone","password");
        assertThat(Base64.getUrlDecoder().decode(refresh)).hasSize(32);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens",String.class)).isEqualTo(AuthSupport.hash(refresh));
        var rotated=refresh(refresh); assertThat(rotated.status()).isEqualTo(200);
        assertThat(refresh(refresh).status()).isEqualTo(401);
        assertThat(post("/api/v1/auth/logout",Map.of("refreshToken",rotated.body().get("refreshToken")),(String)rotated.body().get("accessToken")).status()).isEqualTo(204);
        assertThat(refresh((String)rotated.body().get("refreshToken")).status()).isEqualTo(401);
    }
    @Test void concurrentRefreshHasOnlyOneWinner() throws Exception {
        active("farmer"); String token=(String)login("farmer").get("refreshToken");
        assertThat(race(()->refresh(token).status(),()->refresh(token).status())).containsExactlyInAnyOrder(200,401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",Integer.class)).isEqualTo(1);
    }
    @Test void logoutRejectsTokenOwnedBySomeoneElse() throws Exception {
        active("farmer"); active("another"); var first=login("farmer"); var second=login("another");
        assertThat(post("/api/v1/auth/logout",Map.of("refreshToken",second.get("refreshToken")),(String)first.get("accessToken")).status()).isEqualTo(401);
        assertThat(refresh((String)second.get("refreshToken")).status()).isEqualTo(200);
    }
    @Test void lockRaceCannotLeaveLiveRefreshAndOldJwtIsBlocked() throws Exception {
        var account=active("farmer"); var pair=login("farmer"); UUID id=UUID.fromString((String)account.get("accountId"));
        var results=race(()->refresh((String)pair.get("refreshToken")).status(),()->{
            new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT account_id FROM accounts WHERE account_id=? FOR UPDATE",UUID.class,id);
                jdbc.update("UPDATE accounts SET status='LOCKED',admin_lock=true WHERE account_id=?",id);
                jdbc.update("UPDATE refresh_tokens SET revoked_at=? WHERE account_id=?",Timestamp.from(clock.instant()),id);
            }); return 204;
        });
        assertThat(results.getFirst()).isIn(200,403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",Integer.class)).isZero();
        var old=post("/api/v1/auth/logout",Map.of("refreshToken",pair.get("refreshToken")),(String)pair.get("accessToken"));
        assertThat(old.status()).isEqualTo(403); assertThat(old.body()).containsEntry("code","ACCOUNT_NOT_ACTIVE");
    }
    @Test void expiredRefreshAndExpiredJwtAreRejected() throws Exception {
        active("farmer"); var pair=login("farmer"); clock.advance(7*86400+1);
        assertThat(refresh((String)pair.get("refreshToken")).status()).isEqualTo(401);
        assertThat(post("/api/v1/auth/logout",Map.of("refreshToken",pair.get("refreshToken")),(String)pair.get("accessToken")).status()).isEqualTo(401);
    }
    @Test void loginRateLimitPersistsAcrossRequestsAndResetsAfterWindow() throws Exception {
        active("farmer");
        for (int i=0;i<10;i++) { assertThat(post("/api/v1/auth/login",Map.of("username","farmer","password","Wrong9!")).status()).isEqualTo(401); }
        var limited=post("/api/v1/auth/login",Map.of("username","farmer","password",PASSWORD));
        assertThat(limited.status()).isEqualTo(429); assertThat(Long.parseLong(limited.retry())).isBetween(1L,900L);
        var spoof=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .servletPath("/api/v1/auth/login").header("X-Forwarded-For","198.51.100.7")
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("username","farmer","password",PASSWORD))))
                .andReturn().getResponse();
        assertThat(spoof.getStatus()).isEqualTo(429);
        clock.advance(901); assertThat(login("farmer")).containsEntry("tokenType","Bearer");
    }
    @Test void seedAdminsAreUnverifiedAndIdempotent() throws Exception {
        var seed=new AuthProperties.SeedAccount("admin-one","admin-one@example.test",PASSWORD);
        service.seedAdmin(seed); service.seedAdmin(seed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM accounts",String.class)).isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject("SELECT reputation_score FROM accounts",Object.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM farmer_profiles",Integer.class)).isZero();
        assertThat(post("/api/v1/auth/login",Map.of("username","admin-one","password",PASSWORD)).status()).isEqualTo(403);
        assertThat(verify("admin-one@example.test",mailbox.get("admin-one@example.test")).status()).isEqualTo(200);
        assertThat(login("admin-one")).containsEntry("role","ADMIN");
    }
    @Test void seedRunnerRequiresTwoDistinctIdentitiesAndCreatesUnverifiedAdmins() {
        var a=new AuthProperties.SeedAccount("admin-a","admin-a@example.test",PASSWORD);
        var b=new AuthProperties.SeedAccount("admin-b","admin-b@example.test",PASSWORD+"B");
        var args=new org.springframework.boot.DefaultApplicationArguments(new String[0]);
        var one=new AuthProperties(settings.otpHmacSecret(),settings.privateKeyPath(),settings.keyId(),settings.publicKeyPath(),
                settings.previousKeyId(),settings.previousPublicKeyPath(),settings.mailFrom(),true,List.of(a));
        assertThatThrownBy(()->new AuthMaintenance(one,service,jdbc,clock).run(args)).isInstanceOf(IllegalStateException.class);
        var two=new AuthProperties(settings.otpHmacSecret(),settings.privateKeyPath(),settings.keyId(),settings.publicKeyPath(),
                settings.previousKeyId(),settings.previousPublicKeyPath(),settings.mailFrom(),true,List.of(a,b));
        new AuthMaintenance(two,service,jdbc,clock).run(args);
        new AuthMaintenance(two,service,jdbc,clock).run(args);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE role='ADMIN' AND status='UNVERIFIED' AND reputation_score IS NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_issuances",Integer.class)).isEqualTo(2);
    }
    @Test void protectedJwtRequestFailsClosedWhenPrimaryStateCannotBeRead() throws Exception {
        active("farmer"); var pair=login("farmer");
        // Isolated disposable Testcontainers database only; restore immediately in finally.
        jdbc.execute("ALTER TABLE accounts RENAME TO accounts_temporarily_unavailable");
        try {
            var result=post("/api/v1/auth/logout",Map.of("refreshToken",pair.get("refreshToken")),(String)pair.get("accessToken"));
            assertThat(result.status()).isEqualTo(503);
            assertThat(result.body()).containsEntry("code","ACCESS_STATE_UNAVAILABLE")
                    .containsEntry("message","Account state is temporarily unavailable");
        } finally { jdbc.execute("ALTER TABLE accounts_temporarily_unavailable RENAME TO accounts"); }
    }
}
