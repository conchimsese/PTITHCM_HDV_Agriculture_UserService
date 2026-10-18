package vn.agriculture.soa.user.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="user.auth.enabled",havingValue="true",matchIfMissing=true)
public class AuthMaintenance implements ApplicationRunner {
    private final AuthProperties settings;
    private final AuthService service;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public AuthMaintenance(AuthProperties settings,AuthService service,JdbcTemplate jdbc,Clock clock) {
        this.settings=settings; this.service=service; this.jdbc=jdbc; this.clock=clock;
    }
    @Override public void run(ApplicationArguments args) {
        if (!settings.seedEnabled()) { return; }
        if (settings.admins()==null || settings.admins().size()<2) { throw new IllegalStateException("Configure at least two independent Admin seeds"); }
        var usernames=new HashSet<String>(); var emails=new HashSet<String>(); var passwords=new HashSet<String>();
        for (var seed:settings.admins()) {
            String username=AuthDtos.normalize(seed.username()), email=AuthDtos.normalize(seed.email());
            try {
                new jakarta.mail.internet.InternetAddress(email,true).validate();
                if (username==null || username.length()<3 || username.length()>100 || email.length()>254
                        || !usernames.add(username) || !emails.add(email) || !passwords.add(seed.password())) { throw new IllegalArgumentException(); }
                AuthSupport.passwordPolicy(seed.password());
            } catch (Exception ex) { throw new IllegalStateException("Admin seeds require distinct valid usernames, emails and passwords; no account created"); }
        }
        settings.admins().forEach(service::seedAdmin);
    }
    @Scheduled(cron="0 0 3 * * *",zone="UTC")
    public void cleanup() {
        var now=clock.instant();
        jdbc.update("DELETE FROM email_verification_otps WHERE used_at IS NOT NULL OR expires_at<?",Timestamp.from(now));
        // More than the full rolling quota window retained, independently of OTP cleanup.
        jdbc.update("DELETE FROM otp_issuances WHERE issued_at<?",Timestamp.from(now.minusSeconds(2*86400)));
        jdbc.update("DELETE FROM login_attempt_windows WHERE window_started_at<?",Timestamp.from(now.minusSeconds(86400)));
    }
}
