package vn.agriculture.soa.user.media;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.error.ApiException;
import vn.agriculture.soa.user.outbox.ProfileOutboxWriter;
import vn.agriculture.soa.user.profile.ProfileDtos;
import vn.agriculture.soa.user.profile.ProfileRepository;

@Service
public class AvatarService {
    private final AvatarImages images;
    private final MediaStorage storage;
    private final JdbcTemplate jdbc;
    private final ProfileRepository profiles;
    private final ProfileOutboxWriter outbox;
    private final Clock clock;
    private final TransactionTemplate writes;
    public AvatarService(AvatarImages images, MediaStorage storage, JdbcTemplate jdbc, ProfileRepository profiles,
                         ProfileOutboxWriter outbox, Clock clock, PlatformTransactionManager manager) {
        this.images=images; this.storage=storage; this.jdbc=jdbc; this.profiles=profiles; this.outbox=outbox; this.clock=clock;
        writes = new TransactionTemplate(manager);
    }
    public record Uploaded(UUID mediaId, String avatarUrl, String contentType, long sizeBytes) { }
    public record Download(byte[] bytes, String contentType) { }

    public Uploaded upload(UUID owner, MultipartFile file) {
        var image = images.sanitize(file);
        UUID mediaId = UUID.randomUUID(), key = UUID.randomUUID();
        String url = "/api/v1/users/media/" + mediaId;
        try {
            return writes.execute(status -> {
                var account = profiles.account(owner, true).orElseThrow(AvatarService::notFound);
                if (account.status() != AccountStatus.ACTIVE) { throw inactive(); }
                lockStorageKey(jdbc, key);
                try { storage.write(key, image.bytes()); } catch (IOException ex) { throw unavailable(); }
                Instant now = clock.instant();
                jdbc.update("""
                        INSERT INTO media_assets(media_id,owner_account_id,resource_id,storage_key,content_type,size_bytes,sha256,created_at,attached_at)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, mediaId, owner, owner, key, image.contentType(), image.bytes().length, sha256(image.bytes()), Timestamp.from(now), Timestamp.from(now));
                if (account.role() != AccountRole.ADMIN) {
                    var before = profiles.details(account).orElseThrow(AvatarService::unavailable);
                    ProfileDtos.Details after = switch (before) {
                        case ProfileDtos.Farmer f -> new ProfileDtos.Farmer(f.fullName(),f.phone(),f.address(),f.province(),f.district(),url);
                        case ProfileDtos.Trader t -> new ProfileDtos.Trader(t.businessName(),t.phone(),t.address(),t.province(),t.district(),url,t.buyingArea());
                    };
                    if (profiles.update(owner, after, now) != 1) { throw unavailable(); }
                    long version = profiles.touchAccount(owner, true, now);
                    outbox.append(owner, account.role(), version, ProfileDtos.snapshot(after, account.createdAt()), now, owner);
                } else {
                    jdbc.update("UPDATE accounts SET updated_at=? WHERE account_id=?", Timestamp.from(now), owner);
                }
                return new Uploaded(mediaId, url, image.contentType(), image.bytes().length);
            });
        } catch (DataAccessException | TransactionException ex) { throw unavailable(); }
    }

    public Download download(UUID caller, UUID mediaId) {
        try {
            var matches = jdbc.query("""
                    SELECT m.storage_key,m.content_type,m.size_bytes,m.sha256
                    FROM media_assets m JOIN accounts owner ON owner.account_id=m.owner_account_id
                    JOIN accounts caller ON caller.account_id=? AND caller.status='ACTIVE'
                    LEFT JOIN farmer_profiles f ON f.farmer_id=owner.account_id
                    LEFT JOIN trader_profiles t ON t.trader_id=owner.account_id
                    WHERE m.media_id=? AND m.attached_at IS NOT NULL AND m.resource_id=owner.account_id
                      AND (owner.account_id=? OR (owner.status='ACTIVE' AND owner.role IN ('FARMER','TRADER')
                        AND coalesce(f.avatar_url,t.avatar_url)=?))
                    """, (rs,row) -> new Asset(rs.getObject("storage_key",UUID.class), rs.getString("content_type"),rs.getLong("size_bytes"),rs.getString("sha256")),
                    caller, mediaId, caller, "/api/v1/users/media/" + mediaId);
            Asset asset = matches.stream().findFirst().orElseThrow(AvatarService::notFound);
            byte[] bytes = storage.read(asset.key());
            if (bytes.length != asset.size() || !sha256(bytes).equals(asset.hash())) { throw unavailable(); }
            return new Download(bytes,asset.type());
        } catch (DataAccessException | IOException ex) { throw unavailable(); }
    }
    private record Asset(UUID key, String type, long size, String hash) { }
    static void lockStorageKey(JdbcTemplate jdbc, UUID key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, key.getMostSignificantBits() ^ key.getLeastSignificantBits());
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEPENDENCY_UNAVAILABLE","Media storage is temporarily unavailable"); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Media not found"); }
    private static ApiException inactive() { return new ApiException(HttpStatus.FORBIDDEN,"ACCOUNT_NOT_ACTIVE","Account is not active"); }
}
