package vn.agriculture.soa.user.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

class AccountStatusMailerTest {
    @Test void enabledWorkerRequiresConfiguredSenderAndSupportAddress() {
        var json=new ObjectMapper(); var sender=mock(JavaMailSender.class);
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        assertThatThrownBy(()->new AccountStatusMailer(json,sender,jdbc,"sender@example.test","",true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SUPPORT_EMAIL");
        assertThatThrownBy(()->new AccountStatusMailer(json,sender,jdbc,"","support@example.test",true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
        assertThatCode(()->new AccountStatusMailer(json,sender,jdbc,"sender@example.test","support@example.test",true)).doesNotThrowAnyException();
    }
}
