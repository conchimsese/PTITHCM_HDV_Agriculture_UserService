package vn.agriculture.soa.user.auth;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(name="user.auth.enabled", havingValue="true", matchIfMissing=true)
@EnableConfigurationProperties(AuthProperties.class)
@EnableScheduling
public class AuthConfiguration {
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean JwtTokens jwtTokens(AuthProperties properties, Clock clock) { return new JwtTokens(properties, clock); }
    @Bean @ConditionalOnMissingBean(OtpMailer.class)
    OtpMailer otpMailer(JavaMailSender sender, AuthProperties properties) {
        if (properties.otpHmacSecret() == null || properties.otpHmacSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("OTP_HMAC_SECRET must contain at least 32 bytes; run local auth setup or supply deployment secrets");
        }
        if (properties.mailFrom() == null || !properties.mailFrom().contains("@")) {
            throw new IllegalStateException("MAIL_FROM must be configured");
        }
        return new SmtpOtpMailer(sender, properties.mailFrom());
    }
    @Bean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(JwtTokens tokens) { return tokens.decoder(); }
}
