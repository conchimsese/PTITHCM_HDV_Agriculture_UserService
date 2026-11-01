package vn.agriculture.soa.user.admin;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.profile.ProfileDtos.*;
import static vn.agriculture.soa.user.admin.AdminDtos.*;

@Repository
public class AdminAccountRepository {
    private final JdbcTemplate jdbc;

    public AdminAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AccountRecord(
            UUID id, String username, String email, AccountRole role, AccountStatus status,
            BigDecimal reputationScore, boolean adminLock, boolean reputationLock,
            long accountVersion, long profileVersion,
            Instant createdAt, Instant updatedAt, Instant emailVerifiedAt
    ) {
        @Override public String toString() { return "AccountRecord[redacted]"; }
    }

    public Optional<AccountRecord> findAccount(UUID id, boolean lock) {
        String sql = """
                SELECT account_id, username, email, role, status, reputation_score, admin_lock, reputation_lock,
                       account_version, profile_version, created_at, updated_at, email_verified_at
                FROM accounts WHERE account_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> {
            var verified = rs.getTimestamp("email_verified_at");
            return new AccountRecord(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("username"),
                    rs.getString("email"),
                    AccountRole.valueOf(rs.getString("role")),
                    AccountStatus.valueOf(rs.getString("status")),
                    rs.getBigDecimal("reputation_score"),
                    rs.getBoolean("admin_lock"),
                    rs.getBoolean("reputation_lock"),
                    rs.getLong("account_version"),
                    rs.getLong("profile_version"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    verified == null ? null : verified.toInstant()
            );
        }, id).stream().findFirst();
    }

    public Optional<Details> findProfileDetails(AccountRecord account) {
        return switch (account.role()) {
            case FARMER -> jdbc.<Details>query(
                    "SELECT full_name, phone, address, province, district, avatar_url FROM farmer_profiles WHERE farmer_id = ?",
                    (rs, row) -> new Farmer(
                            rs.getString("full_name"), rs.getString("phone"), rs.getString("address"),
                            rs.getString("province"), rs.getString("district"), rs.getString("avatar_url")
                    ), account.id()).stream().findFirst();
            case TRADER -> jdbc.<Details>query(
                    "SELECT business_name, phone, address, province, district, avatar_url, buying_area FROM trader_profiles WHERE trader_id = ?",
                    (rs, row) -> new Trader(
                            rs.getString("business_name"), rs.getString("phone"), rs.getString("address"),
                            rs.getString("province"), rs.getString("district"), rs.getString("avatar_url"),
                            rs.getString("buying_area")
                    ), account.id()).stream().findFirst();
            case ADMIN -> Optional.empty();
        };
    }

    public PagedAccounts listAccounts(int page, int size, AccountRole role, AccountStatus status) {
        List<Object> countParams = new ArrayList<>();
        StringBuilder countSql = new StringBuilder("SELECT count(*) FROM accounts WHERE 1=1");
        if (role != null) {
            countSql.append(" AND role = ?");
            countParams.add(role.name());
        }
        if (status != null) {
            countSql.append(" AND status = ?");
            countParams.add(status.name());
        }

        Long total = jdbc.queryForObject(countSql.toString(), Long.class, countParams.toArray());
        long totalElements = total == null ? 0 : total;

        List<Object> queryParams = new ArrayList<>(countParams);
        StringBuilder querySql = new StringBuilder("""
                SELECT account_id, username, email, role, status, reputation_score, admin_lock, reputation_lock,
                       created_at, email_verified_at
                FROM accounts WHERE 1=1
                """);
        if (role != null) {
            querySql.append(" AND role = ?");
        }
        if (status != null) {
            querySql.append(" AND status = ?");
        }
        querySql.append(" ORDER BY created_at DESC, account_id DESC LIMIT ? OFFSET ?");
        queryParams.add(size);
        queryParams.add((long) page * size);

        List<AccountSummary> items = jdbc.query(querySql.toString(), (rs, row) -> {
            return new AccountSummary(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("username"),
                    rs.getString("email"),
                    AccountRole.valueOf(rs.getString("role")),
                    AccountStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant()
            );
        }, queryParams.toArray());

        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PagedAccounts(items, page, size, totalElements, totalPages);
    }

    public void lockAdminMutations() {
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('user-service-admin-mutations'))");
    }

    public int countActiveAdmins() {
        return jdbc.queryForObject("SELECT count(*) FROM accounts WHERE role = 'ADMIN' AND status = 'ACTIVE'", Integer.class);
    }

    public int revokeAllRefreshTokens(UUID accountId, Instant now) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), accountId);
    }

    public int updateStatus(UUID accountId, AccountStatus status, boolean adminLock, Instant now) {
        return jdbc.update("""
                UPDATE accounts
                SET status = ?, admin_lock = ?, account_version = account_version + 1, updated_at = ?
                WHERE account_id = ?
                """, status.name(), adminLock, Timestamp.from(now), accountId);
    }

    public int updateProfileDetails(UUID accountId, Details profile, Instant now) {
        var timestamp = Timestamp.from(now);
        return switch (profile) {
            case Farmer f -> jdbc.update("""
                    UPDATE farmer_profiles
                    SET full_name = ?, phone = ?, address = ?, province = ?, district = ?, avatar_url = ?, updated_at = ?
                    WHERE farmer_id = ?
                    """, f.fullName(), f.phone(), f.address(), f.province(), f.district(), f.avatarUrl(), timestamp, accountId);
            case Trader t -> jdbc.update("""
                    UPDATE trader_profiles
                    SET business_name = ?, phone = ?, address = ?, province = ?, district = ?, avatar_url = ?, buying_area = ?, updated_at = ?
                    WHERE trader_id = ?
                    """, t.businessName(), t.phone(), t.address(), t.province(), t.district(), t.avatarUrl(), t.buyingArea(), timestamp, accountId);
        };
    }

    public long touchAccount(UUID accountId, boolean publicChanged, Instant now) {
        return jdbc.queryForObject("""
                UPDATE accounts
                SET updated_at = ?, profile_version = profile_version + ?
                WHERE account_id = ?
                RETURNING profile_version
                """, Long.class, Timestamp.from(now), publicChanged ? 1 : 0, accountId);
    }

    public boolean ownsAttachedAvatar(UUID mediaId, UUID owner) {
        return !jdbc.queryForList("""
                SELECT media_id FROM media_assets
                WHERE media_id = ? AND owner_account_id = ? AND resource_id = ? AND attached_at IS NOT NULL
                FOR SHARE
                """, UUID.class, mediaId, owner, owner).isEmpty();
    }
}
