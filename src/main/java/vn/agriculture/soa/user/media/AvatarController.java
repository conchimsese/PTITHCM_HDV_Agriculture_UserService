package vn.agriculture.soa.user.media;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AvatarController {
    private final AvatarService avatars;
    public AvatarController(AvatarService avatars) { this.avatars=avatars; }
    @PostMapping(value="/api/v1/users/me/avatar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AvatarService.Uploaded> upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(201).header("Cache-Control","private, no-store")
                .body(avatars.upload(UUID.fromString(jwt.getSubject()),file));
    }
    @GetMapping("/api/v1/users/media/{mediaId}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        var media = avatars.download(UUID.fromString(jwt.getSubject()), mediaId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType()))
                .contentLength(media.bytes().length).header("Cache-Control","private, no-store")
                .header("X-Content-Type-Options","nosniff").body(media.bytes());
    }
}
