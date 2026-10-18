package vn.agriculture.soa.user.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import vn.agriculture.soa.user.account.AccountRole;

public final class AuthDtos {
    private AuthDtos() { }
    public static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
    public record Profile(@Size(max=150) String fullName, @Size(max=200) String businessName,
                          @Size(max=20) String phone, @Size(max=255) String address,
                          @Size(max=100) String province, @Size(max=100) String district,
                          @Size(max=500) String avatarUrl, @Size(max=255) String buyingArea) { }
    public record Register(@NotBlank @Size(min=3,max=100) String username,
                           @NotBlank @Email @Size(max=254) String email,
                           @NotNull String password, @NotNull AccountRole role,
                           @NotNull @Valid Profile profile) {
        public Register { username = normalize(username); email = normalize(email); }
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record Verify(@NotBlank @Email @Size(max=254) String email, @NotNull @Pattern(regexp="[0-9]{6}") String otp) {
        public Verify { email = normalize(email); }
        @Override public String toString() { return "Verify[redacted]"; }
    }
    public record Resend(@NotBlank @Email @Size(max=254) String email) {
        public Resend { email = normalize(email); }
        @Override public String toString() { return "Resend[redacted]"; }
    }
    public record Login(@NotBlank @Size(min=3,max=100) String username, @NotNull @Size(max=72) String password) {
        public Login { username = normalize(username); }
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Refresh(@NotBlank @Size(max=256) String refreshToken) {
        @Override public String toString() { return "Refresh[redacted]"; }
    }
    public record Registered(UUID accountId, String username, String email, AccountRole role,
                             String status, Instant createdAt, String verificationEmailStatus) { }
    public record TokenPair(String accessToken, String refreshToken, String tokenType,
                            int expiresIn, UUID accountId, AccountRole role) {
        @Override public String toString() { return "TokenPair[redacted]"; }
    }
}
