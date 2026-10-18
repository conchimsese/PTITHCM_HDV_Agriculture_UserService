package vn.agriculture.soa.user.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static vn.agriculture.soa.user.auth.AuthDtos.*;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="user.auth.enabled",havingValue="true",matchIfMissing=true)
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }
    @PostMapping("/register")
    public ResponseEntity<Registered> register(@RequestBody @Valid Register request) {
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.register(request));
    }
    @PostMapping("/verify-email")
    public Map<String,String> verify(@RequestBody @Valid Verify request) { service.verify(request); return Map.of("status","ACTIVE"); }
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resend(@RequestBody @Valid Resend request) { service.resend(request.email()); return ResponseEntity.accepted().header("Cache-Control","no-store").build(); }
    @PostMapping("/login")
    public ResponseEntity<TokenPair> login(@RequestBody @Valid Login request,HttpServletRequest http) {
        // Container remote address only; forwarded headers are disabled, never trust client X-Forwarded-For.
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.login(request,http.getRemoteAddr()));
    }
    @PostMapping("/refresh")
    public ResponseEntity<TokenPair> refresh(@RequestBody @Valid Refresh request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.refreshToken(request.refreshToken()));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,@RequestBody @Valid Refresh request) {
        service.logout(UUID.fromString(jwt.getSubject()),request.refreshToken());
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
