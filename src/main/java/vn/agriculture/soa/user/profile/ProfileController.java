package vn.agriculture.soa.user.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.agriculture.soa.user.account.AccountRole;
import static vn.agriculture.soa.user.profile.ProfileDtos.*;

@RestController
public class ProfileController {
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) { this.profiles=profiles; }

    @GetMapping("/api/v1/users/me")
    public ResponseEntity<PrivateProfile> me(@AuthenticationPrincipal Jwt jwt) {
        return privateResponse(profiles.me(UUID.fromString(jwt.getSubject())));
    }
    @PutMapping("/api/v1/users/me")
    public ResponseEntity<PrivateProfile> put(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode profile) {
        return privateResponse(profiles.put(UUID.fromString(jwt.getSubject()),profile));
    }
    @GetMapping("/api/v1/users/{accountId}")
    public ResponseEntity<PublicProfile> user(@PathVariable UUID accountId) { return publicResponse(accountId,null); }
    @GetMapping("/api/v1/farmers/{farmerId}")
    public ResponseEntity<PublicProfile> farmer(@PathVariable UUID farmerId) { return publicResponse(farmerId,AccountRole.FARMER); }
    @GetMapping("/api/v1/traders/{traderId}")
    public ResponseEntity<PublicProfile> trader(@PathVariable UUID traderId) { return publicResponse(traderId,AccountRole.TRADER); }

    private ResponseEntity<PrivateProfile> privateResponse(PrivateProfile body) {
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(body);
    }
    private ResponseEntity<PublicProfile> publicResponse(UUID id,AccountRole role) {
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(profiles.publicProfile(id,role));
    }
}
