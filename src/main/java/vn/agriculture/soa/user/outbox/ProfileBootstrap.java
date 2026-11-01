package vn.agriculture.soa.user.outbox;

import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.profile.ProfileDtos;
import vn.agriculture.soa.user.profile.ProfileRepository;

@Component
public class ProfileBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ProfileRepository profiles;
    private final ProfileOutboxWriter outbox;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final boolean enabled;
    public ProfileBootstrap(JdbcTemplate jdbc,ProfileRepository profiles,ProfileOutboxWriter outbox,Clock clock,
                            PlatformTransactionManager manager,@Value("${user.outbox.bootstrap-enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.profiles=profiles; this.outbox=outbox; this.clock=clock; tx=new TransactionTemplate(manager); this.enabled=enabled;
    }
    @Override public void run(ApplicationArguments args) { if (enabled) { snapshots(); } }
    /** Opt-in operator job; keyset paging, fresh state under the same lock as business writes. */
    public void snapshots() {
        UUID cursor=null;
        while (true) {
            var ids=cursor==null?jdbc.queryForList("SELECT account_id FROM accounts WHERE role<>'ADMIN' ORDER BY account_id LIMIT 100",UUID.class):
                    jdbc.queryForList("SELECT account_id FROM accounts WHERE role<>'ADMIN' AND account_id>? ORDER BY account_id LIMIT 100",UUID.class,cursor);
            if (ids.isEmpty()) { return; }
            for (UUID id:ids) {
                tx.executeWithoutResult(status->{
                    var account=profiles.account(id,true).orElseThrow();
                    if (account.role()==AccountRole.ADMIN) { return; }
                    var now=clock.instant();
                    long version=account.profileVersion();
                    if (version==0) { version=profiles.touchAccount(id,true,now); }
                    var snapshot=account.status()==AccountStatus.ACTIVE?
                            ProfileDtos.snapshot(profiles.details(account).orElseThrow(),account.createdAt()):null;
                    outbox.append(id,account.role(),version,snapshot,now,null);
                });
            }
            cursor=ids.getLast();
        }
    }
}
