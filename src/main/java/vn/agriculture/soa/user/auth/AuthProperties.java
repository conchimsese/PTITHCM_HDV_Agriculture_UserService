package vn.agriculture.soa.user.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("user.auth")
public record AuthProperties(String otpHmacSecret, String privateKeyPath, String keyId,
                             String publicKeyPath, String previousKeyId, String previousPublicKeyPath, String mailFrom,
                             boolean seedEnabled, List<SeedAccount> admins) {
    public record SeedAccount(String username, String email, String password) {
        @Override public String toString() { return "SeedAccount[redacted]"; }
    }
    @Override public String toString() { return "AuthProperties[redacted]"; }
}
