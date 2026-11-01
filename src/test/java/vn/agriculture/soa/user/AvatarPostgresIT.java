package vn.agriculture.soa.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.auth.JwtTokens;
import vn.agriculture.soa.user.auth.OtpMailer;
import vn.agriculture.soa.user.media.*;

@SpringBootTest(properties={"user.auth.enabled=true","user.auth.seed-enabled=false","debug=false","user.media.cleanup-cron=-"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class AvatarPostgresIT {
    private static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Path WORK=work();
    private static final Path MEDIA=WORK.resolve("media");
    private static Path work() {
        try {
            Path dir=Files.createTempDirectory("user-avatar-it-");
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
            Files.writeString(dir.resolve("private.pem"),Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            Files.writeString(dir.resolve("public.pem"),Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            return dir;
        } catch (Exception ex) { throw new IllegalStateException("Test setup failed",ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        DB.start();
        p.add("spring.datasource.url",DB::getJdbcUrl); p.add("spring.datasource.username",DB::getUsername); p.add("spring.datasource.password",DB::getPassword);
        p.add("user.auth.otp-hmac-secret",()->"test-only-avatar-secret-at-least-32-bytes");
        p.add("user.auth.private-key-path",()->WORK.resolve("private.pem").toString());
        p.add("user.auth.public-key-path",()->WORK.resolve("public.pem").toString());
        p.add("user.auth.key-id",()->"avatar-test"); p.add("user.media.storage-dir",MEDIA::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokens tokens;
    @Autowired MediaCleanup cleanup;
    @Autowired AvatarService avatars;
    @Autowired vn.agriculture.soa.user.admin.AdminAccountService admins;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean MediaStorage storage;
    @MockitoBean OtpMailer mail;
    private record User(UUID id,String access,AccountRole role) { }
    @BeforeEach void reset() { jdbc.execute("TRUNCATE accounts CASCADE"); }
    private User user(AccountRole role) {
        UUID id=UUID.randomUUID(); String name="avatar_"+id.toString().substring(0,8);
        jdbc.update("INSERT INTO accounts(account_id,username,email,password_hash,role,status,email_verified_at,reputation_score) VALUES (?,?,?,?,?,'ACTIVE',now(),?)",
                id,name,name+"@example.test","test-fixture-hash",role.name(),role==AccountRole.ADMIN?null:10);
        if (role==AccountRole.FARMER) { jdbc.update("INSERT INTO farmer_profiles(farmer_id,full_name) VALUES (?,?)",id,"Avatar Farmer"); }
        if (role==AccountRole.TRADER) { jdbc.update("INSERT INTO trader_profiles(trader_id,business_name) VALUES (?,?)",id,"Avatar Trader"); }
        return new User(id,tokens.issue(id,role),role);
    }
    private byte[] png() throws Exception {
        var output=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB),"png",output); return output.toByteArray();
    }
    private MockHttpServletResponse upload(User user) throws Exception { return upload(user,png()); }
    private MockHttpServletResponse upload(User user,byte[] bytes) throws Exception {
        String path="/api/v1/users/me/avatar";
        return mvc.perform(multipart(path).file(new MockMultipartFile("file","../../escape.png","text/plain",bytes))
                .servletPath(path).header("Authorization","Bearer "+user.access())).andReturn().getResponse();
    }
    private JsonNode body(MockHttpServletResponse response) throws Exception { return json.readTree(response.getContentAsByteArray()); }
    private MockHttpServletResponse read(User user,String path) throws Exception {
        return mvc.perform(get(path).servletPath(path).header("Authorization","Bearer "+user.access())).andReturn().getResponse();
    }
    private UUID key(UUID id) { return jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE media_id=?",UUID.class,id); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private void age(UUID key) throws Exception { Files.setLastModifiedTime(MEDIA.resolve(key.toString()),FileTime.from(Instant.now().minusSeconds(90000))); }

    @Test void farmerAndTraderUploadUpdateProfileAndPublicSnapshotAtomically() throws Exception {
        for (AccountRole role:List.of(AccountRole.FARMER,AccountRole.TRADER)) {
            User owner=user(role); var uploaded=upload(owner); assertThat(uploaded.getStatus()).isEqualTo(201);
            JsonNode result=body(uploaded); assertThat(result.size()).isEqualTo(4);
            String url=result.get("avatarUrl").asText(); UUID mediaId=UUID.fromString(result.get("mediaId").asText());
            assertThat(body(read(owner,"/api/v1/users/me")).get("profile").get("avatarUrl").asText()).isEqualTo(url);
            assertThat(jdbc.queryForObject("SELECT profile_version FROM accounts WHERE account_id=?",Long.class,owner.id())).isEqualTo(1);
            JsonNode event=json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=?",String.class,owner.id()));
            assertThat(event.get("data").get("profile").get("avatarUrl").asText()).isEqualTo(url);
            assertThat(event.toString()).doesNotContain("email","phone","password");
            var downloaded=read(owner,url); assertThat(downloaded.getStatus()).isEqualTo(200);
            assertThat(downloaded.getContentType()).isEqualTo("image/png");
            assertThat(downloaded.getHeader("Cache-Control")).isEqualTo("private, no-store");
            assertThat(downloaded.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(downloaded.getContentAsByteArray().length).isEqualTo(result.get("sizeBytes").asInt());
            assertThat(new MediaStorage(MEDIA.toString()).read(key(mediaId))).isEqualTo(downloaded.getContentAsByteArray());
        }
    }
    @Test void adminAvatarIsPersistentOwnerOnlyAndDoesNotCreateProfileOrPublicEvent() throws Exception {
        User admin=user(AccountRole.ADMIN), otherAdmin=user(AccountRole.ADMIN), farmer=user(AccountRole.FARMER);
        var result=body(upload(admin)); String url=result.get("avatarUrl").asText();
        assertThat(read(admin,url).getStatus()).isEqualTo(200);
        assertThat(read(otherAdmin,url).getStatus()).isEqualTo(404);
        assertThat(read(farmer,url).getStatus()).isEqualTo(404);
        assertThat(body(read(admin,"/api/v1/users/me")).get("profile").isNull()).isTrue();
        assertThat(count("outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM farmer_profiles WHERE farmer_id=?",Integer.class,admin.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trader_profiles WHERE trader_id=?",Integer.class,admin.id())).isZero();
        UUID id=UUID.fromString(result.get("mediaId").asText());
        assertThat(new MediaStorage(MEDIA.toString()).read(key(id))).isEqualTo(read(admin,url).getContentAsByteArray());
    }
    @Test void activeCallersCanReadOnlyCurrentPublicAvatarAndOwnerCanReadHistory() throws Exception {
        User owner=user(AccountRole.FARMER), viewer=user(AccountRole.TRADER);
        String oldUrl=body(upload(owner)).get("avatarUrl").asText();
        assertThat(read(viewer,oldUrl).getStatus()).isEqualTo(200);
        String current=body(upload(owner)).get("avatarUrl").asText();
        assertThat(read(viewer,oldUrl).getStatus()).isEqualTo(404);
        assertThat(read(owner,oldUrl).getStatus()).isEqualTo(200);
        assertThat(read(viewer,current).getStatus()).isEqualTo(200);
        jdbc.update("UPDATE accounts SET status='LOCKED',admin_lock=true WHERE account_id=?",owner.id());
        assertThat(read(viewer,current).getStatus()).isEqualTo(404);
        assertThat(read(owner,current).getStatus()).isEqualTo(403);
    }
    @Test void rejectsUnauthenticatedLockedMalformedAndMissingUploads() throws Exception {
        User owner=user(AccountRole.FARMER); String path="/api/v1/users/me/avatar";
        assertThat(mvc.perform(multipart(path).file(new MockMultipartFile("file",png())).servletPath(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(multipart(path).servletPath(path).header("Authorization","Bearer "+owner.access())).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(owner,"<script/>".getBytes()).getStatus()).isEqualTo(415);
        assertThat(upload(owner,new byte[AvatarImages.MAX_BYTES+1]).getStatus()).isEqualTo(413);
        assertThat(count("media_assets")).isZero();
        jdbc.update("UPDATE accounts SET status='INACTIVE' WHERE account_id=?",owner.id());
        assertThat(upload(owner).getStatus()).isEqualTo(403);
    }
    @Test void guessedMediaIdAndPathTraversalAreNotReadable() throws Exception {
        User owner=user(AccountRole.FARMER);
        assertThat(read(owner,"/api/v1/users/media/"+UUID.randomUUID()).getStatus()).isEqualTo(404);
        assertThat(read(owner,"/api/v1/users/media/not-a-uuid").getStatus()).isEqualTo(400);
        upload(owner);
        try (var files=Files.list(MEDIA)) {
            assertThat(files.filter(Files::isRegularFile).map(p->p.getFileName().toString()).toList())
                    .allSatisfy(name->assertThat(UUID.fromString(name).toString()).isEqualTo(name));
        }
        assertThat(Files.exists(WORK.resolve("escape.png"))).isFalse();
    }
    @Test void filesystemFailureDoesNotCommitMetadataProfileOrOutbox() throws Exception {
        User owner=user(AccountRole.FARMER);
        doThrow(new IOException("test-only storage failure")).when(storage).write(any(UUID.class),any(byte[].class));
        var failed=upload(owner); assertThat(failed.getStatus()).isEqualTo(503);
        assertThat(failed.getContentAsString()).doesNotContain(MEDIA.toString(),"test-only storage failure");
        assertThat(count("media_assets")).isZero(); assertThat(count("outbox")).isZero();
        assertThat(body(read(owner,"/api/v1/users/me")).get("profile").get("avatarUrl").isNull()).isTrue();
    }
    @Test void outboxFailureLeavesOnlyAFileOrphanAndCleanupNeverDeletesAttachedMedia() throws Exception {
        User owner=user(AccountRole.FARMER);
        JsonNode good=body(upload(owner)); UUID goodKey=key(UUID.fromString(good.get("mediaId").asText())); age(goodKey);
        Set<Path> before; try (var files=Files.list(MEDIA)) { before=new HashSet<>(files.toList()); }
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT phase4_fail CHECK (false) NOT VALID");
        try {
            assertThat(upload(owner).getStatus()).isEqualTo(503);
            assertThat(count("media_assets")).isEqualTo(1); assertThat(count("outbox")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT profile_version FROM accounts WHERE account_id=?",Long.class,owner.id())).isEqualTo(1);
            assertThat(body(read(owner,"/api/v1/users/me")).get("profile").get("avatarUrl")).isEqualTo(good.get("avatarUrl"));
        } finally { jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT phase4_fail"); }
        List<Path> orphans; try (var files=Files.list(MEDIA)) { orphans=files.filter(p->!before.contains(p)).toList(); }
        assertThat(orphans).hasSize(1); Path orphan=orphans.getFirst();
        cleanup.cleanup(); assertThat(orphan).exists();
        age(UUID.fromString(orphan.getFileName().toString())); cleanup.cleanup();
        assertThat(orphan).doesNotExist(); assertThat(MEDIA.resolve(goodKey.toString())).exists();
        assertThat(read(owner,good.get("avatarUrl").asText()).getStatus()).isEqualTo(200);
    }
    @Test void cleanupDeletesOldUnattachedMetadataButKeepsYoungFiles() throws Exception {
        User owner=user(AccountRole.FARMER); UUID old=UUID.randomUUID(), young=UUID.randomUUID();
        storage.write(old,png()); storage.write(young,png()); age(old);
        jdbc.update("INSERT INTO media_assets(media_id,owner_account_id,storage_key,content_type,size_bytes,sha256,created_at) VALUES (?,?,?,'image/png',1,?,now()-interval '25 hours')",
                UUID.randomUUID(),owner.id(),old,"a".repeat(64));
        cleanup.cleanup();
        assertThat(MEDIA.resolve(old.toString())).doesNotExist(); assertThat(MEDIA.resolve(young.toString())).exists();
        assertThat(count("media_assets")).isZero();
    }
    @Test void cleanupAlsoRemovesOldUnattachedMetadataWhenFileIsAlreadyMissing() {
        User owner=user(AccountRole.FARMER);
        jdbc.update("INSERT INTO media_assets(media_id,owner_account_id,storage_key,content_type,size_bytes,sha256,created_at) VALUES (?,?,?,'image/png',1,?,now()-interval '25 hours')",
                UUID.randomUUID(),owner.id(),UUID.randomUUID(),"a".repeat(64));
        cleanup.cleanup(); assertThat(count("media_assets")).isZero();
    }
    @Test void cleanupFailsClosedWhenDatabaseIsUnavailable() throws Exception {
        UUID orphan=UUID.randomUUID(); storage.write(orphan,png()); age(orphan);
        jdbc.execute("ALTER TABLE media_assets RENAME TO media_assets_unavailable");
        try { cleanup.cleanup(); assertThat(MEDIA.resolve(orphan.toString())).exists(); }
        finally { jdbc.execute("ALTER TABLE media_assets_unavailable RENAME TO media_assets"); }
    }
    @Test void missingOrCorruptPersistedBytesReturn503WithoutLeakingStoragePath() throws Exception {
        User owner=user(AccountRole.FARMER); JsonNode result=body(upload(owner));
        Path path=MEDIA.resolve(key(UUID.fromString(result.get("mediaId").asText())).toString());
        Files.writeString(path,"corrupt");
        var corrupt=read(owner,result.get("avatarUrl").asText());
        assertThat(corrupt.getStatus()).isEqualTo(503); assertThat(corrupt.getContentAsString()).doesNotContain(path.toString());
        Files.delete(path); assertThat(read(owner,result.get("avatarUrl").asText()).getStatus()).isEqualTo(503);
    }
    @Test void concurrentUploadsProduceOrderedVersionsAndMatchingCurrentSnapshot() throws Exception {
        User owner=user(AccountRole.FARMER);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return upload(owner).getStatus();});
            var b=pool.submit(()->{gate.await();return upload(owner).getStatus();}); gate.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(201); assertThat(b.get(15,TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(count("media_assets")).isEqualTo(2); assertThat(count("outbox")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT profile_version FROM accounts WHERE account_id=?",Long.class,owner.id())).isEqualTo(2);
        JsonNode latest=json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=? ORDER BY (payload->'data'->>'profileVersion')::bigint DESC LIMIT 1",String.class,owner.id()));
        assertThat(latest.get("data").get("profile").get("avatarUrl"))
                .isEqualTo(body(read(owner,"/api/v1/users/me")).get("profile").get("avatarUrl"));
    }
    @Test void cleanupWaitsForInFlightUploadBeforeCheckingReferences() throws Exception {
        User owner=user(AccountRole.ADMIN); UUID key=UUID.randomUUID(); storage.write(key,png()); age(key);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.query("SELECT pg_advisory_xact_lock(?)",rs->{},key.getMostSignificantBits()^key.getLeastSignificantBits());
                locked.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) { throw new IllegalStateException("Test timeout"); } }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                jdbc.update("INSERT INTO media_assets(media_id,owner_account_id,resource_id,storage_key,content_type,size_bytes,sha256,attached_at) VALUES (?,?,?,?,'image/png',1,?,now())",
                        UUID.randomUUID(),owner.id(),owner.id(),key,"a".repeat(64));
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var cleaner=pool.submit(cleanup::cleanup);
            release.countDown(); writer.get(10,TimeUnit.SECONDS); cleaner.get(10,TimeUnit.SECONDS);
        }
        assertThat(MEDIA.resolve(key.toString())).exists(); assertThat(count("media_assets")).isEqualTo(1);
    }
    @Test void uploadRacingAccountLockCannotMakeLockedProfilePublic() throws Exception {
        User owner=user(AccountRole.FARMER), admin=user(AccountRole.ADMIN);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1); byte[] bytes=png();
            var uploading=pool.submit(()->{
                gate.await();
                try { avatars.upload(owner.id(),new MockMultipartFile("file",bytes)); return 201; }
                catch (vn.agriculture.soa.user.error.ApiException ex) { return ex.status().value(); }
            });
            var locking=pool.submit(()->{gate.await(); return admins.updateStatus(admin.id(),owner.id(),vn.agriculture.soa.user.account.AccountStatus.LOCKED,"Race");});
            gate.countDown(); assertThat(uploading.get(15,TimeUnit.SECONDS)).isIn(201,403); locking.get(15,TimeUnit.SECONDS);
        }
        JsonNode latest=json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE aggregate_id=? AND event_type='USER_PROFILE_UPDATED' ORDER BY (payload->'data'->>'profileVersion')::bigint DESC LIMIT 1",String.class,owner.id()));
        assertThat(latest.get("data").get("publicVisible").asBoolean()).isFalse();
        assertThat(latest.get("data").get("profile").isNull()).isTrue();
    }

    @Test void realJarMultipartUploadSurvivesJvmRestartWithSameDatabaseAndDirectory() throws Exception {
        User owner=user(AccountRole.FARMER);
        Path config=WORK.resolve("restart.properties");
        var settings=new Properties();
        settings.setProperty("spring.datasource.url",DB.getJdbcUrl());
        settings.setProperty("spring.datasource.username",DB.getUsername());
        settings.setProperty("spring.datasource.password",DB.getPassword());
        settings.setProperty("user.auth.otp-hmac-secret","test-only-avatar-secret-at-least-32-bytes");
        settings.setProperty("user.auth.private-key-path",WORK.resolve("private.pem").toString());
        settings.setProperty("user.auth.public-key-path",WORK.resolve("public.pem").toString());
        settings.setProperty("user.auth.key-id","avatar-test");
        settings.setProperty("user.auth.mail-from","avatar-restart@example.test");
        settings.setProperty("user.auth.seed-enabled","false");
        settings.setProperty("user.media.storage-dir",MEDIA.toString());
        settings.setProperty("user.media.cleanup-cron","-");
        try (var output=Files.newOutputStream(config)) { settings.store(output,"Isolated Testcontainers configuration only"); }
        var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        String avatarUrl; byte[] expected;
        int firstPort=freePort(); Process first=startJar(config,firstPort,"restart-first.log");
        try {
            awaitHealth(client,first,firstPort);
            var multipart=new ByteArrayOutputStream();
            multipart.write("--avatar-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            multipart.write(png()); multipart.write("\r\n--avatar-boundary--\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+firstPort+"/api/v1/users/me/avatar"))
                    .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+owner.access())
                    .header("Content-Type","multipart/form-data; boundary=avatar-boundary")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build();
            var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(201);
            JsonNode uploaded=json.readTree(response.body()); avatarUrl=uploaded.get("avatarUrl").asText();
            var downloaded=httpRead(client,firstPort,avatarUrl,owner.access());
            assertThat(downloaded.statusCode()).isEqualTo(200); expected=downloaded.body();
            // Exercise the real servlet multipart limit, which MockMvc bypasses.
            var oversized=new ByteArrayOutputStream();
            oversized.write("--large-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes());
            oversized.write(new byte[AvatarImages.MAX_BYTES+1]); oversized.write("\r\n--large-boundary--\r\n".getBytes());
            var largeRequest=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+firstPort+"/api/v1/users/me/avatar"))
                    .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+owner.access())
                    .header("Content-Type","multipart/form-data; boundary=large-boundary")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(oversized.toByteArray())).build();
            assertThat(client.send(largeRequest,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
        } finally { stopJar(first); }
        int secondPort=freePort(); Process second=startJar(config,secondPort,"restart-second.log");
        try {
            awaitHealth(client,second,secondPort);
            var downloaded=httpRead(client,secondPort,avatarUrl,owner.access());
            assertThat(downloaded.statusCode()).isEqualTo(200); assertThat(downloaded.body()).isEqualTo(expected);
            assertThat(downloaded.headers().firstValue("Cache-Control")).contains("private, no-store");
            var me=httpRead(client,secondPort,"/api/v1/users/me",owner.access());
            assertThat(json.readTree(me.body()).get("profile").get("avatarUrl").asText()).isEqualTo(avatarUrl);
        } finally { stopJar(second); }
    }
    private int freePort() throws IOException {
        try (var socket=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
    private Process startJar(Path config,int port,String log) throws IOException {
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-Djava.awt.headless=true","-jar",Path.of("target/user-service-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString(),
                "--spring.config.additional-location="+config.toUri(),"--spring.profiles.active=avatar-restart-test",
                "--server.address=127.0.0.1","--server.port="+port);
        return command.redirectErrorStream(true).redirectOutput(WORK.resolve(log).toFile()).start();
    }
    private void awaitHealth(java.net.http.HttpClient client,Process process,int port) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime()<deadline && process.isAlive()) {
            try {
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/actuator/health"))
                        .timeout(java.time.Duration.ofSeconds(2)).GET().build();
                if (client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()==200) { return; }
            } catch (IOException ignored) { }
            Thread.sleep(200);
        }
        throw new AssertionError("Isolated restart-test JAR did not become healthy; see "+WORK);
    }
    private java.net.http.HttpResponse<byte[]> httpRead(java.net.http.HttpClient client,int port,String path,String token) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
                .timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token).GET().build();
        return client.send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    }
    private void stopJar(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10,TimeUnit.SECONDS); }
    }
}
