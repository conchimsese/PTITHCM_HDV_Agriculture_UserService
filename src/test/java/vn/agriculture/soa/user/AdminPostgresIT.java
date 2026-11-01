package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.admin.AdminDtos.UpdateStatusRequest;
import vn.agriculture.soa.user.admin.AdminAccountService;
import vn.agriculture.soa.user.error.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.agriculture.soa.user.auth.AuthDtos.Profile;
import vn.agriculture.soa.user.auth.AuthDtos.Register;
import vn.agriculture.soa.user.auth.JwtTokens;
import vn.agriculture.soa.user.auth.OtpMailer;

@SpringBootTest(properties = {"user.auth.enabled=true", "user.auth.seed-enabled=false", "debug=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AdminPostgresIT {
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Path KEYS = keys();

    private static Path keys() {
        try {
            Path dir = Files.createTempDirectory("user-admin-it-");
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            Files.writeString(dir.resolve("private.pem"), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            Files.writeString(dir.resolve("public.pem"), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            return dir;
        } catch (Exception ex) {
            throw new IllegalStateException("Test key setup failed");
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        DB.start();
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
        properties.add("user.auth.otp-hmac-secret", () -> "test-only-admin-otp-secret-at-least-32-bytes");
        properties.add("user.auth.private-key-path", () -> KEYS.resolve("private.pem").toString());
        properties.add("user.auth.public-key-path", () -> KEYS.resolve("public.pem").toString());
        properties.add("user.auth.key-id", () -> "admin-test-current");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokens tokens;
    @Autowired AdminAccountService admins;
    @Autowired PasswordEncoder passwords;
    @MockitoBean OtpMailer mail;

    private record User(UUID id, String access, AccountRole role) { }
    private record Reply(int status, JsonNode body, String cache) { }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE accounts CASCADE");
    }

    private User user(AccountRole role, AccountStatus status) {
        UUID id = UUID.randomUUID();
        String name = "user_" + id.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO accounts(account_id, username, email, password_hash, role, status, email_verified_at, reputation_score)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?)
                """, id, name, name + "@example.test", "test-hash", role.name(), status.name(),
                role == AccountRole.ADMIN ? null : BigDecimal.valueOf(10.0));

        if (role == AccountRole.FARMER) {
            jdbc.update("INSERT INTO farmer_profiles(farmer_id, full_name, phone, address, province, district) VALUES (?, ?, ?, ?, ?, ?)",
                    id, "Farmer Name", "0900000000", "Farmer Address", "Dong Thap", "Cao Lanh");
        } else if (role == AccountRole.TRADER) {
            jdbc.update("INSERT INTO trader_profiles(trader_id, business_name, phone, address, province, district, buying_area) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, "Trader Business", "0911111111", "Trader Address", "Can Tho", "Ninh Kieu", "Mekong");
        }
        return new User(id, tokens.issue(id, role), role);
    }

    private Reply request(MockHttpServletRequestBuilder req) throws Exception {
        var response = mvc.perform(req).andReturn().getResponse();
        JsonNode body = response.getContentAsByteArray().length == 0 ? json.nullNode() : json.readTree(response.getContentAsByteArray());
        return new Reply(response.getStatus(), body, response.getHeader("Cache-Control"));
    }

    private Reply getAs(String path, User caller) throws Exception {
        String servlet = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        return request(get(path).servletPath(servlet).header("Authorization", "Bearer " + caller.access()));
    }

    private Reply postAs(String path, User caller, Object body) throws Exception {
        return request(post(path).servletPath(path).header("Authorization", "Bearer " + caller.access())
                .contentType("application/json").content(json.writeValueAsBytes(body)));
    }

    private Reply patchAs(String path, User caller, Object body) throws Exception {
        return request(patch(path).servletPath(path).header("Authorization", "Bearer " + caller.access())
                .contentType("application/json").content(json.writeValueAsBytes(body)));
    }

    private Reply deleteAs(String path, User caller) throws Exception {
        return request(delete(path).servletPath(path).header("Authorization", "Bearer " + caller.access()));
    }

    @Test
    void accessControl_requiresAdminRoleAndActiveStatus() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        User lockedAdmin = user(AccountRole.ADMIN, AccountStatus.LOCKED);

        // Anonymous
        Reply unauth = request(get("/api/v1/admin/accounts").servletPath("/api/v1/admin/accounts"));
        assertThat(unauth.status()).isEqualTo(401);

        // Non-admin (FARMER)
        Reply forbidden = getAs("/api/v1/admin/accounts", farmer);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.body().get("code").asText()).isEqualTo("FORBIDDEN");

        // Locked admin
        Reply locked = getAs("/api/v1/admin/accounts", lockedAdmin);
        assertThat(locked.status()).isEqualTo(403);
        assertThat(locked.body().get("code").asText()).isEqualTo("ACCOUNT_NOT_ACTIVE");

        // Active admin
        Reply ok = getAs("/api/v1/admin/accounts", admin);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.cache()).isEqualTo("private, no-store");
    }

    @Test
    void createAccount_createsFarmerAndTraderUnverified() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);

        // Create Farmer
        Register reqFarmer = new Register("new_farmer", "farmer@example.com", "Secret@123", AccountRole.FARMER,
                new Profile("New Farmer", null, "0901234567", "Address 1", "An Giang", "Long Xuyen", null, null));
        Reply resFarmer = postAs("/api/v1/admin/accounts", admin, reqFarmer);
        assertThat(resFarmer.status()).isEqualTo(201);
        assertThat(resFarmer.body().get("role").asText()).isEqualTo("FARMER");
        assertThat(resFarmer.body().get("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(resFarmer.body().get("verificationEmailStatus").asText()).isIn("SENT", "FAILED");

        // Create Trader
        Register reqTrader = new Register("new_trader", "trader@example.com", "Secret@123", AccountRole.TRADER,
                new Profile(null, "New Trader Business", "0909999999", "Address 2", "Can Tho", "Ninh Kieu", null, "Mekong Delta"));
        Reply resTrader = postAs("/api/v1/admin/accounts", admin, reqTrader);
        assertThat(resTrader.status()).isEqualTo(201);
        assertThat(resTrader.body().get("role").asText()).isEqualTo("TRADER");
        assertThat(resTrader.body().get("status").asText()).isEqualTo("UNVERIFIED");

        // Reject ADMIN role creation
        Register reqAdmin = new Register("new_admin", "admin@example.com", "Secret@123", AccountRole.ADMIN,
                new Profile(null, null, null, null, null, null, null, null));
        Reply resAdmin = postAs("/api/v1/admin/accounts", admin, reqAdmin);
        assertThat(resAdmin.status()).isEqualTo(400);
        assertThat(resAdmin.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");

        // Duplicate username
        Reply dup = postAs("/api/v1/admin/accounts", admin, reqFarmer);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("code").asText()).isEqualTo("ACCOUNT_ALREADY_EXISTS");
    }

    @Test
    void listAccounts_pagedAndFiltered() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        User trader = user(AccountRole.TRADER, AccountStatus.LOCKED);

        // List all
        Reply all = getAs("/api/v1/admin/accounts?page=0&size=10", admin);
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().get("totalElements").asInt()).isEqualTo(3);
        assertThat(all.body().get("items").size()).isEqualTo(3);

        // Filter role FARMER
        Reply farmers = getAs("/api/v1/admin/accounts?role=FARMER", admin);
        assertThat(farmers.status()).isEqualTo(200);
        assertThat(farmers.body().get("totalElements").asInt()).isEqualTo(1);
        assertThat(farmers.body().get("items").get(0).get("role").asText()).isEqualTo("FARMER");

        // Filter status LOCKED
        Reply locked = getAs("/api/v1/admin/accounts?status=LOCKED", admin);
        assertThat(locked.status()).isEqualTo(200);
        assertThat(locked.body().get("totalElements").asInt()).isEqualTo(1);
        assertThat(locked.body().get("items").get(0).get("status").asText()).isEqualTo("LOCKED");
    }

    @Test
    void getAccount_returnsDetailsAndRejectsUnknown() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);

        Reply found = getAs("/api/v1/admin/accounts/" + farmer.id(), admin);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.body().get("accountId").asText()).isEqualTo(farmer.id().toString());
        assertThat(found.body().get("role").asText()).isEqualTo("FARMER");
        assertThat(found.body().get("profile").get("fullName").asText()).isEqualTo("Farmer Name");

        // Admin detail has null profile
        Reply adminDetail = getAs("/api/v1/admin/accounts/" + admin.id(), admin);
        assertThat(adminDetail.status()).isEqualTo(200);
        assertThat(adminDetail.body().get("profile").isNull()).isTrue();

        // Non-existent
        Reply notFound = getAs("/api/v1/admin/accounts/" + UUID.randomUUID(), admin);
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.body().get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void patchProfile_allowlistAndValidation() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);

        // Valid partial patch
        Map<String, Object> validPatch = Map.of("phone", "0988776655", "district", "Thap Muoi");
        Reply res = patchAs("/api/v1/admin/accounts/" + farmer.id(), admin, validPatch);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("profile").get("phone").asText()).isEqualTo("0988776655");
        assertThat(res.body().get("profile").get("district").asText()).isEqualTo("Thap Muoi");
        assertThat(res.body().get("profile").get("fullName").asText()).isEqualTo("Farmer Name"); // unchanged

        // Outbox event was published for public snapshot change (district changed)
        int events = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='USER_PROFILE_UPDATED'",
                Integer.class, farmer.id());
        assertThat(events).isGreaterThanOrEqualTo(1);

        // Invalid field outside allowlist
        Map<String, Object> invalidField = Map.of("role", "ADMIN");
        Reply resInvalid = patchAs("/api/v1/admin/accounts/" + farmer.id(), admin, invalidField);
        assertThat(resInvalid.status()).isEqualTo(400);
        assertThat(resInvalid.body().get("code").asText()).isEqualTo("VALIDATION_ERROR");

        // Blank display name
        Map<String, Object> blankName = Map.of("fullName", "   ");
        Reply resBlank = patchAs("/api/v1/admin/accounts/" + farmer.id(), admin, blankName);
        assertThat(resBlank.status()).isEqualTo(400);

        // Cannot patch ADMIN profile
        Reply patchAdmin = patchAs("/api/v1/admin/accounts/" + admin.id(), admin, validPatch);
        assertThat(patchAdmin.status()).isEqualTo(400);
    }

    @Test
    void updateStatus_lockRevokesTokensAndBlocksSubsequentRequests() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);

        // Insert refresh token for farmer
        jdbc.update("INSERT INTO refresh_tokens(refresh_token_id, account_id, token_hash, expires_at) VALUES (?, ?, ?, now() + interval '7 days')",
                UUID.randomUUID(), farmer.id(), "1".repeat(64));

        // Lock farmer
        Reply lockRes = patchAs("/api/v1/admin/accounts/" + farmer.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.LOCKED, "Suspected fraud"));
        assertThat(lockRes.status()).isEqualTo(200);
        assertThat(lockRes.body().get("status").asText()).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("SELECT admin_lock FROM accounts WHERE account_id=?", Boolean.class, farmer.id())).isTrue();

        // Refresh token must be revoked
        int activeTokens = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE account_id=? AND revoked_at IS NULL",
                Integer.class, farmer.id());
        assertThat(activeTokens).isZero();

        // Existing JWT is blocked immediately on subsequent requests
        Reply farmerMe = request(get("/api/v1/users/me").servletPath("/api/v1/users/me").header("Authorization", "Bearer " + farmer.access()));
        assertThat(farmerMe.status()).isEqualTo(403);
        assertThat(farmerMe.body().get("code").asText()).isEqualTo("ACCOUNT_NOT_ACTIVE");

        // Outbox contains ACCOUNT_LOCKED and EMAIL intent
        int lockedEvents = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='ACCOUNT_LOCKED'",
                Integer.class, farmer.id());
        assertThat(lockedEvents).isEqualTo(2); // 1 KAFKA + 1 EMAIL

        // Outbox contains USER_PROFILE_UPDATED with publicVisible = false
        int profileHiddenEvents = jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='USER_PROFILE_UPDATED'
                AND (payload->'data'->>'publicVisible')::boolean = false
                """, Integer.class, farmer.id());
        assertThat(profileHiddenEvents).isGreaterThanOrEqualTo(1);

        // Unlock farmer
        Reply unlockRes = patchAs("/api/v1/admin/accounts/" + farmer.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.ACTIVE, "Resolved"));
        assertThat(unlockRes.status()).isEqualTo(200);
        assertThat(unlockRes.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT admin_lock FROM accounts WHERE account_id=?", Boolean.class, farmer.id())).isFalse();

        // Outbox contains ACCOUNT_UNLOCKED
        int unlockedEvents = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='ACCOUNT_UNLOCKED'",
                Integer.class, farmer.id());
        assertThat(unlockedEvents).isEqualTo(2); // 1 KAFKA + 1 EMAIL
    }

    @Test
    void updateStatus_restoreInvariants() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);

        // Account with reputation_lock cannot be restored to ACTIVE
        User repLockedUser = user(AccountRole.FARMER, AccountStatus.LOCKED);
        jdbc.update("UPDATE accounts SET reputation_lock = true, reputation_score = 0 WHERE account_id = ?", repLockedUser.id());
        Reply repRes = patchAs("/api/v1/admin/accounts/" + repLockedUser.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.ACTIVE, "Try restore"));
        assertThat(repRes.status()).isEqualTo(409);
        assertThat(repRes.body().get("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");

        // Account unverified cannot be activated directly
        User unverifiedUser = user(AccountRole.FARMER, AccountStatus.UNVERIFIED);
        jdbc.update("UPDATE accounts SET email_verified_at = NULL WHERE account_id = ?", unverifiedUser.id());
        Reply unvRes = patchAs("/api/v1/admin/accounts/" + unverifiedUser.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.ACTIVE, "Try restore"));
        assertThat(unvRes.status()).isEqualTo(409);
        assertThat(unvRes.body().get("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void selfAction_and_lastAdminProtection() throws Exception {
        User admin1 = user(AccountRole.ADMIN, AccountStatus.ACTIVE);

        // Self-lock rejected
        Reply selfLock = patchAs("/api/v1/admin/accounts/" + admin1.id() + "/status", admin1,
                new UpdateStatusRequest(AccountStatus.LOCKED, "Self lock"));
        assertThat(selfLock.status()).isEqualTo(409);
        assertThat(selfLock.body().get("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");

        // Self-delete rejected
        Reply selfDelete = deleteAs("/api/v1/admin/accounts/" + admin1.id(), admin1);
        assertThat(selfDelete.status()).isEqualTo(409);
        assertThat(selfDelete.body().get("code").asText()).isEqualTo("INVALID_STATE_TRANSITION");

        // Last active admin protection
        User admin2 = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        // Admin1 disables Admin2 -> OK because Admin1 remains active
        Reply disAdmin2 = patchAs("/api/v1/admin/accounts/" + admin2.id() + "/status", admin1,
                new UpdateStatusRequest(AccountStatus.INACTIVE, "Disable other"));
        assertThat(disAdmin2.status()).isEqualTo(200);

        // Now Admin1 is the only active admin. Another call to disable Admin1 fails!
        // (Even if requested by another caller or if Admin2 tried before being blocked)
        Reply disAdmin1 = patchAs("/api/v1/admin/accounts/" + admin1.id() + "/status", admin1,
                new UpdateStatusRequest(AccountStatus.INACTIVE, "Disable last"));
        assertThat(disAdmin1.status()).isEqualTo(409);
    }

    @Test
    void concurrentAdmins_cannotBothDisableEachOther() throws Exception {
        User admin1 = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User admin2 = user(AccountRole.ADMIN, AccountStatus.ACTIVE);

        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        var statuses = new CopyOnWriteArrayList<Integer>();

        Callable<Void> task1 = () -> {
            barrier.await();
            try {
                admins.updateStatus(admin1.id(), admin2.id(), AccountStatus.INACTIVE, "Disable 2");
                statuses.add(200);
            } catch (ApiException ex) { statuses.add(ex.status().value()); }
            return null;
        };

        Callable<Void> task2 = () -> {
            barrier.await();
            try {
                admins.updateStatus(admin2.id(), admin1.id(), AccountStatus.INACTIVE, "Disable 1");
                statuses.add(200);
            } catch (ApiException ex) { statuses.add(ex.status().value()); }
            return null;
        };

        var f1 = executor.submit(task1);
        var f2 = executor.submit(task2);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one should succeed (200) and one must fail (409)
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // Exactly one active admin remains in the DB
        int activeAdmins = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE role='ADMIN' AND status='ACTIVE'", Integer.class);
        assertThat(activeAdmins).isEqualTo(1);
    }

    @Test
    void accountDtosAndPaginationFollowContract() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        Reply page = getAs("/api/v1/admin/accounts?size=999", admin);
        assertThat(page.body().get("size").asInt()).isEqualTo(100);
        assertThat(page.body().get("page").asInt()).isZero();
        assertThat(page.body().get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.body().get("items").get(0).size()).isEqualTo(6);
        assertThat(getAs("/api/v1/admin/accounts?page=-1", admin).status()).isEqualTo(400);
        assertThat(getAs("/api/v1/admin/accounts?role=UNKNOWN", admin).status()).isEqualTo(400);
        assertThat(getAs("/api/v1/admin/accounts?page=5", admin).body().get("items")).isEmpty();
        Reply detail = getAs("/api/v1/admin/accounts/" + farmer.id(), admin);
        assertThat(detail.body().size()).isEqualTo(8);
        assertThat(detail.body().has("passwordHash")).isFalse();
        assertThat(detail.body().has("reputationScore")).isFalse();
    }

    @Test
    void accountOutboxHasContractPayloadAndStatusChangesAreIdempotent() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        String path = "/api/v1/admin/accounts/" + farmer.id() + "/status";
        var command = new UpdateStatusRequest(AccountStatus.LOCKED, "Private explanation with farmer@example.test");
        assertThat(patchAs(path, admin, command).status()).isEqualTo(200);
        assertThat(patchAs(path, admin, command).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT account_version FROM accounts WHERE account_id=?", Long.class, farmer.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=?", Integer.class, farmer.id())).isEqualTo(3);
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=? AND channel='KAFKA' AND event_type='ACCOUNT_LOCKED'", String.class, farmer.id());
        JsonNode event = json.readTree(payload);
        assertThat(event.get("data").size()).isEqualTo(3);
        assertThat(event.get("data").get("reasonCode").asText()).isEqualTo("ADMIN_LOCK");
        assertThat(event.get("data").get("accountVersion").asLong()).isEqualTo(1);
        assertThat(event.get("recipientIds").get(0).asText()).isEqualTo(farmer.id().toString());
        assertThat(event.get("referenceId").asText()).isEqualTo(farmer.id().toString());
        assertThat(payload).doesNotContain("farmer@example.test", "Private explanation", "token", "password");
    }

    @Test
    void emailOutboxFailureRollsBackStatusRevocationVersionsAndKafkaIntent() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        jdbc.update("INSERT INTO refresh_tokens(refresh_token_id,account_id,token_hash,expires_at) VALUES (?,?,?,now()+interval '7 days')",
                UUID.randomUUID(), farmer.id(), "a".repeat(64));
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase3_fail_email CHECK (channel <> 'EMAIL') NOT VALID");
        try {
            assertThat(patchAs("/api/v1/admin/accounts/" + farmer.id() + "/status", admin,
                    new UpdateStatusRequest(AccountStatus.LOCKED, "Rollback")).status()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?", String.class, farmer.id())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT account_version+profile_version FROM accounts WHERE account_id=?", Long.class, farmer.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE account_id=? AND revoked_at IS NULL", Integer.class, farmer.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=?", Integer.class, farmer.id())).isZero();
        } finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase3_fail_email"); }
    }

    @Test
    void profileOutboxFailureRollsBackAdminPatch() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase3_fail_profile CHECK (false) NOT VALID");
        try {
            assertThat(patchAs("/api/v1/admin/accounts/" + farmer.id(), admin, Map.of("fullName", "Rollback")).status()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT full_name FROM farmer_profiles WHERE farmer_id=?", String.class, farmer.id())).isEqualTo("Farmer Name");
            assertThat(jdbc.queryForObject("SELECT profile_version FROM accounts WHERE account_id=?", Long.class, farmer.id())).isZero();
        } finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase3_fail_profile"); }
    }

    @Test
    void reputationLockAndAdminLockRemainIndependentAndAdminRestoreNeedsNoScore() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.LOCKED);
        jdbc.update("UPDATE accounts SET reputation_lock=true,reputation_score=0 WHERE account_id=?", farmer.id());
        assertThat(patchAs("/api/v1/admin/accounts/" + farmer.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.LOCKED, "Admin also locks")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT admin_lock AND reputation_lock FROM accounts WHERE account_id=?", Boolean.class, farmer.id())).isTrue();
        assertThat(patchAs("/api/v1/admin/accounts/" + farmer.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.ACTIVE, "Cannot bypass reputation")).status()).isEqualTo(409);
        User otherAdmin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        assertThat(patchAs("/api/v1/admin/accounts/" + otherAdmin.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.LOCKED, "Lock")).status()).isEqualTo(200);
        assertThat(patchAs("/api/v1/admin/accounts/" + otherAdmin.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.ACTIVE, "Restore")).status()).isEqualTo(200);
    }

    private List<Reply> race(Callable<Reply> first, Callable<Reply> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); return first.call(); });
            var b = pool.submit(() -> { gate.await(); return second.call(); });
            gate.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void adminLockRacingRefreshOrLoginLeavesNoLiveSession() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        jdbc.update("UPDATE accounts SET password_hash=? WHERE account_id=?", passwords.encode("TestOnly9!"), farmer.id());
        String username = jdbc.queryForObject("SELECT username FROM accounts WHERE account_id=?", String.class, farmer.id());
        var credentials = Map.of("username", username, "password", "TestOnly9!");
        Reply login = request(post("/api/v1/auth/login").servletPath("/api/v1/auth/login")
                .contentType("application/json").content(json.writeValueAsBytes(credentials)));
        assertThat(login.status()).isEqualTo(200);
        String path = "/api/v1/admin/accounts/" + farmer.id() + "/status";
        var lock = new UpdateStatusRequest(AccountStatus.LOCKED, "Race");
        var results = race(() -> request(post("/api/v1/auth/refresh").servletPath("/api/v1/auth/refresh")
                        .contentType("application/json").content(json.writeValueAsBytes(Map.of("refreshToken", login.body().get("refreshToken").asText())))),
                () -> patchAs(path, admin, lock));
        assertThat(results.get(0).status()).isIn(200, 403);
        assertThat(results.get(1).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE account_id=? AND revoked_at IS NULL", Integer.class, farmer.id())).isZero();
        assertThat(patchAs(path, admin, new UpdateStatusRequest(AccountStatus.ACTIVE, "Restore")).status()).isEqualTo(200);
        var loginRace = race(() -> request(post("/api/v1/auth/login").servletPath("/api/v1/auth/login")
                        .contentType("application/json").content(json.writeValueAsBytes(credentials))),
                () -> patchAs(path, admin, lock));
        assertThat(loginRace.get(0).status()).isIn(200, 403);
        assertThat(loginRace.get(1).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE account_id=? AND revoked_at IS NULL", Integer.class, farmer.id())).isZero();
    }

    @Test
    void adminCreateCommitsBeforeSmtpAndKeepsAccountWhenMailFails() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email='smtp-failure@example.test'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_verification_otps", Integer.class)).isEqualTo(1);
            return false;
        }).when(mail).send(org.mockito.ArgumentMatchers.eq("smtp-failure@example.test"), org.mockito.ArgumentMatchers.anyString());
        Register request = new Register("smtp_failure", "smtp-failure@example.test", "TestOnly9!", AccountRole.FARMER,
                new Profile("Mail failure farmer", null, null, null, null, null, null, null));
        Reply created = postAs("/api/v1/admin/accounts", admin, request);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("verificationEmailStatus").asText()).isEqualTo("FAILED");
        assertThat(created.body().get("status").asText()).isEqualTo("UNVERIFIED");
        org.mockito.Mockito.verify(mail).send(org.mockito.ArgumentMatchers.eq("smtp-failure@example.test"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void hiddenProfilePatchOnlyPublishesTombstoneAndPrivatePatchHasNoPublicEvent() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);
        assertThat(patchAs("/api/v1/admin/accounts/" + farmer.id(), admin, Map.of("phone", "0999999999")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=?", Integer.class, farmer.id())).isZero();
        assertThat(patchAs("/api/v1/admin/accounts/" + farmer.id() + "/status", admin,
                new UpdateStatusRequest(AccountStatus.LOCKED, "Hidden")).status()).isEqualTo(200);
        assertThat(patchAs("/api/v1/admin/accounts/" + farmer.id(), admin, Map.of("fullName", "Hidden name")).status()).isEqualTo(200);
        var events = jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_id=? AND event_type='USER_PROFILE_UPDATED'", String.class, farmer.id());
        assertThat(events).hasSize(2);
        for (String payload : events) {
            JsonNode data = json.readTree(payload).get("data");
            assertThat(data.get("publicVisible").asBoolean()).isFalse();
            assertThat(data.get("profile").isNull()).isTrue();
            assertThat(payload).doesNotContain("Hidden name", "0999999999");
        }
    }

    @Test
    void softDelete_setsInactiveAndRevokesTokens() throws Exception {
        User admin = user(AccountRole.ADMIN, AccountStatus.ACTIVE);
        User farmer = user(AccountRole.FARMER, AccountStatus.ACTIVE);

        jdbc.update("INSERT INTO refresh_tokens(refresh_token_id, account_id, token_hash, expires_at) VALUES (?, ?, ?, now() + interval '7 days')",
                UUID.randomUUID(), farmer.id(), "2".repeat(64));

        Reply delRes = deleteAs("/api/v1/admin/accounts/" + farmer.id(), admin);
        assertThat(delRes.status()).isEqualTo(204);

        // Status is now INACTIVE
        String status = jdbc.queryForObject("SELECT status FROM accounts WHERE account_id=?", String.class, farmer.id());
        assertThat(status).isEqualTo("INACTIVE");

        // Refresh token revoked
        int liveTokens = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE account_id=? AND revoked_at IS NULL",
                Integer.class, farmer.id());
        assertThat(liveTokens).isZero();
    }
}
