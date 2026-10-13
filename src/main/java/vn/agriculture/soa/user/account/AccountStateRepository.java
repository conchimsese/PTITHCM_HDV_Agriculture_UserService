package vn.agriculture.soa.user.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountStateRepository {
    private final JdbcTemplate jdbc;

    public AccountStateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AccessState> find(UUID accountId) {
        return jdbc.query("SELECT account_id, status, role FROM accounts WHERE account_id = ?",
                (rs, row) -> {
                    var status = AccountStatus.valueOf(rs.getString("status"));
                    return new AccessState(rs.getObject("account_id", UUID.class), status,
                            AccountRole.valueOf(rs.getString("role")), status == AccountStatus.ACTIVE);
                }, accountId).stream().findFirst();
    }
}
