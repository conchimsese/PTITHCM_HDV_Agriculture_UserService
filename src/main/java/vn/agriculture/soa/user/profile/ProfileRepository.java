package vn.agriculture.soa.user.profile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import static vn.agriculture.soa.user.profile.ProfileDtos.*;

@Repository
public class ProfileRepository {
    private final JdbcTemplate jdbc;
    public ProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Account(UUID id, String username, String email, AccountRole role, AccountStatus status,
                          Instant createdAt, Instant verifiedAt, long profileVersion) {
        @Override public String toString() { return "ProfileAccount[redacted]"; }
    }

    public Optional<Account> account(UUID id, boolean lock) {
        String sql = """
                SELECT account_id,username,email,role,status,created_at,email_verified_at,profile_version
                FROM accounts WHERE account_id=?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> {
            var verified = rs.getTimestamp("email_verified_at");
            return new Account(rs.getObject("account_id", UUID.class), rs.getString("username"), rs.getString("email"),
                    AccountRole.valueOf(rs.getString("role")), AccountStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(), verified == null ? null : verified.toInstant(), rs.getLong("profile_version"));
        }, id).stream().findFirst();
    }

    public Optional<Details> details(Account account) {
        return switch (account.role()) {
            case FARMER -> jdbc.<Details>query("SELECT full_name,phone,address,province,district,avatar_url FROM farmer_profiles WHERE farmer_id=?",
                    (rs, row) -> new Farmer(rs.getString("full_name"), rs.getString("phone"), rs.getString("address"),
                            rs.getString("province"), rs.getString("district"), rs.getString("avatar_url")), account.id()).stream().findFirst();
            case TRADER -> jdbc.<Details>query("SELECT business_name,phone,address,province,district,avatar_url,buying_area FROM trader_profiles WHERE trader_id=?",
                    (rs, row) -> new Trader(rs.getString("business_name"), rs.getString("phone"), rs.getString("address"),
                            rs.getString("province"), rs.getString("district"), rs.getString("avatar_url"), rs.getString("buying_area")), account.id()).stream().findFirst();
            case ADMIN -> Optional.empty();
        };
    }

    public Optional<PublicProfile> publicProfile(UUID id, AccountRole expectedRole) {
        String sql = """
                SELECT a.account_id,a.created_at,coalesce(f.full_name,t.business_name) AS display_name,
                       coalesce(f.avatar_url,t.avatar_url) AS avatar_url,coalesce(f.province,t.province) AS province,
                       coalesce(f.district,t.district) AS district
                FROM accounts a
                LEFT JOIN farmer_profiles f ON f.farmer_id=a.account_id
                LEFT JOIN trader_profiles t ON t.trader_id=a.account_id
                WHERE a.account_id=? AND a.status='ACTIVE' AND a.role IN ('FARMER','TRADER')
                  AND coalesce(f.farmer_id,t.trader_id) IS NOT NULL
                """ + (expectedRole == null ? "" : " AND a.role=?");
        Object[] params = expectedRole == null ? new Object[]{id} : new Object[]{id, expectedRole.name()};
        return jdbc.query(sql, (rs, row) -> new PublicProfile(rs.getObject("account_id", UUID.class),
                rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("province"),
                rs.getString("district"), rs.getTimestamp("created_at").toInstant()), params).stream().findFirst();
    }

    public int update(UUID id, Details profile, Instant now) {
        var timestamp = java.sql.Timestamp.from(now);
        return switch (profile) {
            case Farmer f -> jdbc.update("UPDATE farmer_profiles SET full_name=?,phone=?,address=?,province=?,district=?,avatar_url=?,updated_at=? WHERE farmer_id=?",
                    f.fullName(),f.phone(),f.address(),f.province(),f.district(),f.avatarUrl(),timestamp,id);
            case Trader t -> jdbc.update("UPDATE trader_profiles SET business_name=?,phone=?,address=?,province=?,district=?,avatar_url=?,buying_area=?,updated_at=? WHERE trader_id=?",
                    t.businessName(),t.phone(),t.address(),t.province(),t.district(),t.avatarUrl(),t.buyingArea(),timestamp,id);
        };
    }

    public boolean ownsAttachedAvatar(UUID mediaId, UUID owner) {
        return !jdbc.queryForList("""
                SELECT media_id FROM media_assets
                WHERE media_id=? AND owner_account_id=? AND resource_id=? AND attached_at IS NOT NULL
                FOR SHARE
                """, UUID.class, mediaId, owner, owner).isEmpty();
    }

    public long touchAccount(UUID id, boolean publicChanged, Instant now) {
        return jdbc.queryForObject("""
                UPDATE accounts SET updated_at=?,profile_version=profile_version+? WHERE account_id=? RETURNING profile_version
                """, Long.class, java.sql.Timestamp.from(now), publicChanged ? 1 : 0, id);
    }
}
