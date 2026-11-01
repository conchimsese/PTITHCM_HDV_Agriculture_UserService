package vn.agriculture.soa.user.media;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaStorageTest {
    @TempDir Path root;
    @Test void reopeningStorageKeepsBytesAndNeverOverwritesExistingFiles() throws Exception {
        UUID key=UUID.randomUUID(); var storage=new MediaStorage(root.toString());
        storage.write(key,new byte[] {1,2,3});
        assertThat(new MediaStorage(root.toString()).read(key)).containsExactly(1,2,3);
        assertThatThrownBy(()->storage.write(key,new byte[] {4})).isInstanceOf(IOException.class);
        assertThat(storage.read(key)).containsExactly(1,2,3);
    }
    @Test void cleanupCandidatesOnlyContainCanonicalRegularUuidFiles() throws Exception {
        UUID key=UUID.randomUUID(); var storage=new MediaStorage(root.toString());
        storage.write(key,new byte[] {1});
        Files.writeString(root.resolve("unrelated.txt"),"keep");
        Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        assertThat(storage.olderThan(Instant.now().plusSeconds(1))).extracting(MediaStorage.Candidate::key).containsExactly(key);
    }
    @Test void readRejectsMissingAndOversizedFiles() throws Exception {
        var storage=new MediaStorage(root.toString()); UUID key=UUID.randomUUID();
        assertThatThrownBy(()->storage.read(key)).isInstanceOf(IOException.class);
        try (var file = new java.io.RandomAccessFile(root.resolve(key.toString()).toFile(),"rw")) { file.setLength(AvatarImages.MAX_BYTES+1L); }
        assertThatThrownBy(()->storage.read(key)).isInstanceOf(IOException.class);
    }
}
