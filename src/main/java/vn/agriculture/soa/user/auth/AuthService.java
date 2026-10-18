package vn.agriculture.soa.user.auth;

import static vn.agriculture.soa.user.auth.AuthDtos.*;
import static vn.agriculture.soa.user.auth.AuthSupport.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.error.ApiException;

@Service
@ConditionalOnProperty(name="user.auth.enabled",havingValue="true",matchIfMissing=true)
public class AuthService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;
    private final OtpMailer mail;
    private final JwtTokens jwt;
    private final Clock clock;
    private final String otpSecret;
    private final String dummyHash;
    private record Account(UUID id, String username, String email, String passwordHash,
                           AccountRole role, String status, Instant createdAt) { }
    private record Delivery(Account account, String code) {
        @Override public String toString() { return "Delivery[redacted]"; }
    }
    private record Outcome<T>(T value, ApiException error) {
        static <T> Outcome<T> ok(T value) { return new Outcome<>(value,null); }
        static <T> Outcome<T> fail(ApiException error) { return new Outcome<>(null,error); }
        T require() { if (error != null) { throw error; } return value; }
    }
    public AuthService(JdbcTemplate jdbc, PlatformTransactionManager manager, PasswordEncoder passwords,
                       OtpMailer mail, JwtTokens jwt, Clock clock, AuthProperties settings) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.passwords=passwords;
        this.mail=mail; this.jwt=jwt; this.clock=clock; this.otpSecret=settings.otpHmacSecret();
        if (otpSecret == null || otpSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("OTP_HMAC_SECRET must contain at least 32 bytes");
        }
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    private static Account account(ResultSet rs,int row) throws SQLException {
        return new Account(rs.getObject("account_id",UUID.class),rs.getString("username"),rs.getString("email"),
                rs.getString("password_hash"),AccountRole.valueOf(rs.getString("role")),rs.getString("status"),rs.getTimestamp("created_at").toInstant());
    }
    private Account byEmail(String email) {
        return jdbc.query("SELECT * FROM accounts WHERE email=? FOR UPDATE",AuthService::account,email).stream().findFirst().orElse(null);
    }
    private Account lock(UUID id) {
        return jdbc.query("SELECT * FROM accounts WHERE account_id=? FOR UPDATE",AuthService::account,id).stream().findFirst().orElse(null);
    }
    private Account insert(String username,String email,String passwordHash,AccountRole role) {
        UUID id=UUID.randomUUID(); Instant now=clock.instant();
        jdbc.update("""
                INSERT INTO accounts(account_id,username,email,password_hash,role,reputation_score,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,id,username,email,passwordHash,role.name(),role==AccountRole.ADMIN?null:10,Timestamp.from(now),Timestamp.from(now));
        return new Account(id,username,email,passwordHash,role,"UNVERIFIED",now);
    }
    public Registered register(Register request) {
        if (request.role()==AccountRole.ADMIN) { throw invalid("Public registration supports FARMER and TRADER only"); }
        passwordPolicy(request.password());
        var profile=request.profile();
        if (profile==null) { throw invalid("Profile is required"); }
        boolean farmer=request.role()==AccountRole.FARMER;
        String name=farmer?profile.fullName():profile.businessName();
        if (name==null || name.isBlank() || (farmer && (profile.businessName()!=null || profile.buyingArea()!=null))
                || (!farmer && profile.fullName()!=null) || profile.avatarUrl()!=null) {
            throw invalid("Profile fields must match the selected role; register avatarUrl must be null");
        }
        String encoded=passwords.encode(request.password());
        Delivery delivery;
        try {
            delivery=tx.execute(status -> {
                Account a=insert(request.username(),request.email(),encoded,request.role());
                if (farmer) {
                    jdbc.update("INSERT INTO farmer_profiles(farmer_id,full_name,phone,address,province,district) VALUES (?,?,?,?,?,?)",
                            a.id(),name.trim(),profile.phone(),profile.address(),profile.province(),profile.district());
                } else {
                    jdbc.update("INSERT INTO trader_profiles(trader_id,business_name,phone,address,province,district,buying_area) VALUES (?,?,?,?,?,?,?)",
                            a.id(),name.trim(),profile.phone(),profile.address(),profile.province(),profile.district(),profile.buyingArea());
                }
                return new Delivery(a,issueOtp(a));
            });
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT,"ACCOUNT_ALREADY_EXISTS","Username or email already exists");
        }
        Account a=delivery.account();
        return new Registered(a.id(),a.username(),a.email(),a.role(),a.status(),a.createdAt(),deliver(delivery)?"SENT":"FAILED");
    }
    private boolean deliver(Delivery delivery) {
        try { return mail.send(delivery.account().email(),delivery.code()); }
        catch (RuntimeException ex) { return false; } // SMTP must not undo committed registration.
    }
    private String otpHash(Account a,UUID otpId,String code) { return hmac(otpSecret,a.id()+":"+otpId+":"+a.email()+":"+code); }
    private String issueOtp(Account a) {
        Instant now=clock.instant();
        var issuance=jdbc.query("SELECT issued_at FROM otp_issuances WHERE email=? AND issued_at>? ORDER BY issued_at DESC",
                (rs,row)->rs.getTimestamp(1).toInstant(),a.email(),Timestamp.from(now.minusSeconds(3600)));
        if (!issuance.isEmpty()) {
            long elapsed=Duration.between(issuance.getFirst(),now).getSeconds();
            if (elapsed<60) { throw new RateLimitedException(60-elapsed); }
            if (issuance.size()>=5) { throw new RateLimitedException(3600-Duration.between(issuance.getLast(),now).getSeconds()); }
        }
        var previous=jdbc.queryForList("SELECT otp_id,code_hash FROM email_verification_otps WHERE account_id=? ORDER BY created_at DESC LIMIT 1",a.id());
        String code;
        do { code=otp(); }
        while (!previous.isEmpty() && previous.getFirst().get("code_hash").equals(otpHash(a,(UUID)previous.getFirst().get("otp_id"),code)));
        jdbc.update("UPDATE email_verification_otps SET invalidated_at=? WHERE account_id=? AND used_at IS NULL AND invalidated_at IS NULL",
                Timestamp.from(now),a.id());
        UUID otpId=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO email_verification_otps(otp_id,account_id,email,code_hash,expires_at,created_at)
                VALUES (?,?,?,?,?,?)
                """,otpId,a.id(),a.email(),otpHash(a,otpId,code),Timestamp.from(now.plusSeconds(600)),Timestamp.from(now));
        jdbc.update("INSERT INTO otp_issuances(issuance_id,account_id,email,issued_at) VALUES (?,?,?,?)",
                UUID.randomUUID(),a.id(),a.email(),Timestamp.from(now));
        return code;
    }
    public void resend(String email) {
        Delivery delivery=tx.execute(status -> {
            Account a=byEmail(email);
            if (a==null || !"UNVERIFIED".equals(a.status())) { return null; }
            return new Delivery(a,issueOtp(a));
        });
        if (delivery!=null && !deliver(delivery)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"EMAIL_DELIVERY_UNAVAILABLE","Verification email could not be sent");
        }
    }
    private ApiException badOtp() { return new ApiException(HttpStatus.BAD_REQUEST,"OTP_INVALID_OR_EXPIRED","Invalid or expired verification code"); }
    public void verify(Verify request) {
        // Return the error, commit failed-attempt counters, THEN throw outside the transaction.
        ApiException error=tx.execute(status -> {
            Account a=byEmail(request.email());
            if (a==null || !"UNVERIFIED".equals(a.status())) { return badOtp(); }
            var codes=jdbc.queryForList("SELECT * FROM email_verification_otps WHERE account_id=? AND used_at IS NULL AND invalidated_at IS NULL FOR UPDATE",a.id());
            if (codes.isEmpty()) { return badOtp(); }
            var code=codes.getFirst(); Instant now=clock.instant();
            if (!((Timestamp)code.get("expires_at")).toInstant().isAfter(now) || ((Number)code.get("attempt_count")).intValue()>=5) { return badOtp(); }
            UUID otpId=(UUID)code.get("otp_id");
            if (!MessageDigest.isEqual(((String)code.get("code_hash")).getBytes(StandardCharsets.US_ASCII),
                    otpHash(a,otpId,request.otp()).getBytes(StandardCharsets.US_ASCII))) {
                jdbc.update("UPDATE email_verification_otps SET attempt_count=attempt_count+1 WHERE otp_id=?",otpId);
                return badOtp();
            }
            jdbc.update("UPDATE email_verification_otps SET used_at=? WHERE otp_id=?",Timestamp.from(now),otpId);
            jdbc.update("UPDATE accounts SET status='ACTIVE',email_verified_at=?,updated_at=?,account_version=account_version+1 WHERE account_id=?",
                    Timestamp.from(now),Timestamp.from(now),a.id());
            return null;
        });
        if (error!=null) { throw error; }
    }
    public TokenPair login(Login request,String ip) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length>72) { throw invalid("Password exceeds 72 UTF-8 bytes"); }
        Outcome<TokenPair> result=tx.execute(status -> {
            String bucket=hmac(otpSecret,ip+"\0"+request.username()); Instant now=clock.instant();
            jdbc.update("INSERT INTO login_attempt_windows(bucket_hash,window_started_at) VALUES (?,?) ON CONFLICT DO NOTHING",bucket,Timestamp.from(now));
            var window=jdbc.queryForMap("SELECT * FROM login_attempt_windows WHERE bucket_hash=? FOR UPDATE",bucket);
            Instant began=((Timestamp)window.get("window_started_at")).toInstant();
            int failures=((Number)window.get("failure_count")).intValue();
            if (!began.plusSeconds(900).isAfter(now)) {
                jdbc.update("UPDATE login_attempt_windows SET window_started_at=?,failure_count=0 WHERE bucket_hash=?",Timestamp.from(now),bucket);
                failures=0; began=now;
            }
            if (failures>=10) { return Outcome.fail(new RateLimitedException(Duration.between(now,began.plusSeconds(900)).getSeconds())); }
            Account a=jdbc.query("SELECT * FROM accounts WHERE username=? FOR UPDATE",AuthService::account,request.username()).stream().findFirst().orElse(null);
            boolean matches=passwords.matches(request.password(),a==null?dummyHash:a.passwordHash());
            if (a==null || !matches) {
                jdbc.update("UPDATE login_attempt_windows SET failure_count=failure_count+1 WHERE bucket_hash=?",bucket);
                return Outcome.fail(unauthorized("INVALID_CREDENTIALS"));
            }
            if (!"ACTIVE".equals(a.status())) { return Outcome.fail(inactive()); }
            jdbc.update("UPDATE login_attempt_windows SET failure_count=0,window_started_at=? WHERE bucket_hash=?",Timestamp.from(now),bucket);
            return Outcome.ok(tokens(a));
        });
        return result.require();
    }
    private TokenPair tokens(Account a) {
        String refresh=refresh(); Instant now=clock.instant();
        // Signing failure rolls back refresh insert and previous-token rotation.
        String access=jwt.issue(a.id(),a.role());
        jdbc.update("INSERT INTO refresh_tokens(refresh_token_id,account_id,token_hash,expires_at,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(),a.id(),hash(refresh),Timestamp.from(now.plusSeconds(7*86400)),Timestamp.from(now));
        return new TokenPair(access,refresh,"Bearer",900,a.id(),a.role());
    }
    public TokenPair refreshToken(String token) {
        return tx.execute(status -> {
            String digest=hash(token);
            var owners=jdbc.queryForList("SELECT account_id FROM refresh_tokens WHERE token_hash=?",UUID.class,digest);
            if (owners.isEmpty()) { throw unauthorized("INVALID_REFRESH_TOKEN"); }
            Account a=lock(owners.getFirst());
            if (a==null) { throw unauthorized("INVALID_REFRESH_TOKEN"); }
            if (!"ACTIVE".equals(a.status())) { throw inactive(); }
            var rows=jdbc.queryForList("SELECT * FROM refresh_tokens WHERE token_hash=? AND account_id=? FOR UPDATE",digest,a.id());
            if (rows.isEmpty()) { throw unauthorized("INVALID_REFRESH_TOKEN"); }
            var row=rows.getFirst(); Instant now=clock.instant();
            if (row.get("revoked_at")!=null || !((Timestamp)row.get("expires_at")).toInstant().isAfter(now)) { throw unauthorized("INVALID_REFRESH_TOKEN"); }
            jdbc.update("UPDATE refresh_tokens SET revoked_at=?,last_used_at=? WHERE refresh_token_id=?",
                    Timestamp.from(now),Timestamp.from(now),row.get("refresh_token_id"));
            return tokens(a);
        });
    }
    public void logout(UUID owner,String token) {
        tx.executeWithoutResult(status -> {
            Account a=lock(owner);
            if (a==null || !"ACTIVE".equals(a.status())) { throw inactive(); }
            var rows=jdbc.queryForList("SELECT refresh_token_id FROM refresh_tokens WHERE token_hash=? AND account_id=? FOR UPDATE",UUID.class,hash(token),owner);
            if (rows.isEmpty()) { throw unauthorized("INVALID_REFRESH_TOKEN"); }
            jdbc.update("UPDATE refresh_tokens SET revoked_at=coalesce(revoked_at,?) WHERE refresh_token_id=?",Timestamp.from(clock.instant()),rows.getFirst());
        });
    }
    public void seedAdmin(AuthProperties.SeedAccount seed) {
        passwordPolicy(seed.password());
        String username=normalize(seed.username()),email=normalize(seed.email());
        String encoded=passwords.encode(seed.password());
        Delivery delivery=tx.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('user-service-admin-seed'))");
            var existing=jdbc.query("SELECT * FROM accounts WHERE username=? OR email=? FOR UPDATE",AuthService::account,username,email);
            if (!existing.isEmpty()) {
                Account a=existing.getFirst();
                if (existing.size()!=1 || a.role()!=AccountRole.ADMIN || !a.username().equals(username) || !a.email().equals(email)) {
                    throw new IllegalStateException("Admin seed identity conflicts with existing account; no automatic merge");
                }
                return null; // Idempotent: never reset password/status or send an OTP on every restart.
            }
            Account a=insert(username,email,encoded,AccountRole.ADMIN);
            return new Delivery(a,issueOtp(a));
        });
        if (delivery!=null) { deliver(delivery); } // User may request resend if SMTP failed.
    }
}
