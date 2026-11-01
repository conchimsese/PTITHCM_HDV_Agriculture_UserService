package vn.agriculture.soa.user.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountStatusMailer {
    private final ObjectMapper json;
    private final JavaMailSender mail;
    private final JdbcTemplate jdbc;
    private final String from;
    private final String support;
    public AccountStatusMailer(ObjectMapper json,JavaMailSender mail,JdbcTemplate jdbc,
                               @Value("${user.auth.mail-from:}") String from,@Value("${user.outbox.support-email:}") String support,
                               @Value("${user.outbox.email-enabled:false}") boolean enabled) {
        this.json=json; this.mail=mail; this.jdbc=jdbc; this.from=from; this.support=support;
        if (enabled) { validateAddress(from); validateAddress(support); }
    }
    private static void validateAddress(String value) {
        try { new jakarta.mail.internet.InternetAddress(value,true).validate(); }
        catch (Exception ex) { throw new IllegalStateException("Email worker requires valid MAIL_FROM and SUPPORT_EMAIL"); }
    }
    public void send(OutboxRepository.Pending event) throws Exception {
        if (!Set.of("ACCOUNT_LOCKED","ACCOUNT_UNLOCKED").contains(event.eventType())) { throw new IllegalArgumentException("Unsupported account email"); }
        var payload=json.readTree(event.payload());
        if (!event.eventType().equals(payload.path("eventType").asText())
                || !event.accountId().toString().equals(payload.path("accountId").asText())) { throw new IllegalArgumentException("Invalid account email intent"); }
        String email=payload.path("email").asText(); validateAddress(email);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE account_id=? AND email=? AND email_verified_at IS NOT NULL)",
                Boolean.class,event.accountId(),email))) { throw new IllegalStateException("Recipient has not verified this account email"); }
        boolean locked=event.eventType().equals("ACCOUNT_LOCKED");
        var message=new SimpleMailMessage(); message.setFrom(from); message.setTo(email);
        message.setSubject(locked?"Tài khoản đã bị khóa":"Tài khoản đã được mở khóa");
        // Do not forward free-form Admin input, evidence or third-party contacts.
        message.setText(locked?"Tài khoản của bạn đã bị khóa. Để được hỗ trợ, vui lòng liên hệ "+support+".":
                "Tài khoản của bạn đã được mở khóa. Bạn có thể đăng nhập lại.");
        mail.send(message);
    }
}
