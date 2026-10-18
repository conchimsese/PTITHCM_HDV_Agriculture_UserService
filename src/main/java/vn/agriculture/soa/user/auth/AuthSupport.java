package vn.agriculture.soa.user.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import vn.agriculture.soa.user.error.ApiException;

public final class AuthSupport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private AuthSupport() { }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static String hmac(String secret, String text) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("OTP hashing unavailable"); }
    }
    public static String otp() { return "%06d".formatted(RANDOM.nextInt(1_000_000)); }
    public static String refresh() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static void passwordPolicy(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.codePoints().noneMatch(Character::isUpperCase)
                || password.codePoints().noneMatch(Character::isLowerCase)
                || password.codePoints().noneMatch(Character::isDigit)
                || password.codePoints().noneMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))) {
            throw invalid("Password must contain upper/lowercase, digit and special character; 8 characters minimum, 72 UTF-8 bytes maximum");
        }
    }
    public static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    public static ApiException unauthorized(String code) { return new ApiException(HttpStatus.UNAUTHORIZED, code, "Invalid credential"); }
    public static ApiException inactive() { return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE", "Account is not active"); }
}
