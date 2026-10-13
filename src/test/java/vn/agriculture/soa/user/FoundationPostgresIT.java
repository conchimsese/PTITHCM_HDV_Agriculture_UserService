package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = "user.internal.keys.gateway=test-only-integration-key-32-bytes-long")
@AutoConfigureMockMvc
class FoundationPostgresIT {
    private static PostgreSQLContainer<?> container;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String external = System.getProperty("test.db.url");
        if (external != null && !external.isBlank()) {
            // Dedicated disposable test DB only. Tests truncate User tables.
            properties.add("spring.datasource.url", () -> external);
            properties.add("spring.datasource.username", () -> System.getProperty("test.db.username", "postgres"));
            properties.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        } else {
            container = new PostgreSQLContainer<>("postgres:17-alpine");
            container.start();
            properties.add("spring.datasource.url", container::getJdbcUrl);
            properties.add("spring.datasource.username", container::getUsername);
            properties.add("spring.datasource.password", container::getPassword);
        }
    }

    @BeforeEach
    void reset() { jdbc.execute("TRUNCATE accounts CASCADE"); }

    private UUID account(String username, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (account_id,username,email,password_hash,role,reputation_score)
                VALUES (?,?,?,?,?,?)
                """, id, username, username + "@example.test", "test-only-hash", role,
                role.equals("ADMIN") ? null : 10);
        return id;
    }

    @Test
    void migrationCreatesBaselineAndIsRepeatable() {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'", String.class))
                .contains("accounts", "farmer_profiles", "trader_profiles", "email_verification_otps",
                        "refresh_tokens", "reputation_history", "outbox", "media_assets", "otp_issuances");
    }

    @Test
    void uniqueIdentityAndNormalizationAreEnforced() {
        account("farmer", "FARMER");
        assertThatThrownBy(() -> account("farmer", "FARMER")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> account(" Farmer ", "FARMER")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> account("trader", "SERVICE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void profilesMustMatchAccountRole() {
        UUID farmer = account("farmer", "FARMER");
        jdbc.update("INSERT INTO farmer_profiles(farmer_id,full_name) VALUES (?,?)", farmer, "Test Farmer");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO trader_profiles(trader_id,business_name) VALUES (?,?)",
                farmer, "Wrong role")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reputationAndActiveStateConstraintsAreEnforced() {
        UUID farmer = account("farmer", "FARMER");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET reputation_score=9.3 WHERE account_id=?", farmer))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET status='ACTIVE' WHERE account_id=?", farmer))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID admin = account("admin", "ADMIN");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET reputation_score=10 WHERE account_id=?", admin))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE accounts SET status='ACTIVE',email_verified_at=now() WHERE account_id=?", admin);
    }

    @Test
    void accessStateReadsCommittedChangesWithoutCaching() throws Exception {
        UUID id = account("farmer", "FARMER");
        jdbc.update("UPDATE accounts SET status='ACTIVE',email_verified_at=now() WHERE account_id=?", id);
        String path = "/internal/v1/accounts/" + id + "/access-state";
        mvc.perform(get(path).servletPath(path).header("X-Service-Key", "test-only-integration-key-32-bytes-long"))
                .andExpect(status().isOk()).andExpect(jsonPath("accessAllowed").value(true));
        jdbc.update("UPDATE accounts SET status='LOCKED',admin_lock=true WHERE account_id=?", id);
        mvc.perform(get(path).servletPath(path).header("X-Service-Key", "test-only-integration-key-32-bytes-long"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("LOCKED"))
                .andExpect(jsonPath("accessAllowed").value(false))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void postgresAccountRowLockSerializesConcurrentWriters() throws Exception {
        UUID id = account("farmer", "FARMER");
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try {
                try (var query = first.prepareStatement("SELECT account_id FROM accounts WHERE account_id=? FOR UPDATE")) {
                    query.setObject(1, id);
                    query.executeQuery().close();
                }
                try (var timeout = second.createStatement()) { timeout.execute("SET LOCAL lock_timeout='200ms'"); }
                try (var update = second.prepareStatement("UPDATE accounts SET account_version=account_version+1 WHERE account_id=?")) {
                    update.setObject(1, id);
                    assertThatThrownBy(update::executeUpdate).isInstanceOf(SQLException.class)
                            .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("55P03"));
                }
            } finally {
                second.rollback();
                first.rollback();
            }
        }
    }

    @Test
    void otpCleanupDoesNotEraseIssuanceQuotaHistory() {
        UUID id = account("farmer", "FARMER");
        jdbc.update("INSERT INTO otp_issuances(issuance_id,account_id,email) VALUES (?,?,?)",
                UUID.randomUUID(), id, "farmer@example.test");
        jdbc.update("""
                INSERT INTO email_verification_otps(otp_id,account_id,email,code_hash,created_at,expires_at)
                VALUES (?,?,?,? , now()-interval '20 minutes',now()-interval '10 minutes')
                """, UUID.randomUUID(), id, "farmer@example.test", "a".repeat(64));
        jdbc.update("DELETE FROM email_verification_otps WHERE expires_at < now()");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_issuances WHERE account_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void healthIsAvailableWithoutExposingDatabaseDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("UP"))
                .andExpect(jsonPath("components").doesNotExist());
    }
}
