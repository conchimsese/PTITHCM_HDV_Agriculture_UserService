package vn.agriculture.soa.user.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** All paths are generated UUIDs under a trusted, private storage root. */
@Component
public class MediaStorage {
    private final Path root;
    public MediaStorage(@Value("${user.media.storage-dir}") String directory) throws IOException {
        Path configured = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        root = configured.toRealPath();
    }
    public void write(UUID key, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path(key), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.force(true);
        }
        // Persist the directory entry where supported; Windows does not expose directory fsync.
        if (!System.getProperty("os.name").startsWith("Windows")) {
            try (var directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
        }
    }
    public byte[] read(UUID key) throws IOException {
        try (var channel = Files.newByteChannel(path(key), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > AvatarImages.MAX_BYTES) { throw new IOException("Invalid stored media size"); }
            var buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) { throw new IOException("Incomplete stored media"); }
            }
            return buffer.array();
        }
    }
    public record Candidate(UUID key, Instant modifiedAt) { }
    public List<Candidate> olderThan(Instant cutoff) throws IOException {
        var candidates = new ArrayList<Candidate>();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) { continue; }
                try {
                    UUID key = UUID.fromString(entry.getFileName().toString());
                    if (!key.toString().equals(entry.getFileName().toString())) { continue; }
                    Instant modified = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant();
                    if (modified.isBefore(cutoff)) { candidates.add(new Candidate(key, modified)); }
                } catch (IllegalArgumentException ignored) { /* Never touch unrelated files. */ }
            }
        }
        return candidates;
    }
    public void delete(UUID key) throws IOException { Files.deleteIfExists(path(key)); }
    private Path path(UUID key) { return root.resolve(key.toString()); }
}
