package vn.agriculture.soa.user.auth;

public interface OtpMailer {
    /** Called only after the account/OTP transaction commits. Never log the code. */
    boolean send(String email, String otp);
}
