package vn.agriculture.soa.user.auth;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpOtpMailer implements OtpMailer {
    private final JavaMailSender sender;
    private final String from;
    public SmtpOtpMailer(JavaMailSender sender, String from) { this.sender = sender; this.from = from; }
    @Override public boolean send(String email, String otp) {
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject("Agriculture SOA - Verify email");
        message.setText("Your email verification code is " + otp + ". It expires in 10 minutes.");
        try { sender.send(message); return true; }
        catch (MailException ex) { return false; }
    }
}
