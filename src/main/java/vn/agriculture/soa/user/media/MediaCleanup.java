package vn.agriculture.soa.user.media;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MediaCleanup {
    private static final Logger log = LoggerFactory.getLogger(MediaCleanup.class);
    private final MediaStorage storage;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public MediaCleanup(MediaStorage storage, JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.storage=storage; this.jdbc=jdbc; this.clock=clock; transactions=new TransactionTemplate(manager);
    }
    @Scheduled(cron="${user.media.cleanup-cron:0 30 3 * * *}",zone="UTC")
    public void cleanup() {
        var cutoff = clock.instant().minus(Duration.ofHours(24));
        try {
            var keys = new java.util.LinkedHashSet<java.util.UUID>();
            storage.olderThan(cutoff).forEach(candidate -> keys.add(candidate.key()));
            keys.addAll(jdbc.queryForList("SELECT storage_key FROM media_assets WHERE attached_at IS NULL AND created_at<? LIMIT 1000",
                    java.util.UUID.class, Timestamp.from(cutoff)));
            for (var key : keys) {
                transactions.executeWithoutResult(status -> {
                    AvatarService.lockStorageKey(jdbc,key);
                    jdbc.update("DELETE FROM media_assets WHERE storage_key=? AND attached_at IS NULL AND created_at<?", key, Timestamp.from(cutoff));
                    if (jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE storage_key=?", Integer.class,key) == 0) {
                        try { storage.delete(key); } catch (IOException ex) { throw AvatarService.unavailable(); }
                    }
                });
            }
        } catch (IOException | org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException | vn.agriculture.soa.user.error.ApiException ex) {
            // No cleanup when the database is unavailable; keep files for the next run.
            log.warn("Media orphan cleanup deferred; storage or database unavailable");
        }
    }
}
