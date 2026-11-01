package vn.agriculture.soa.user.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    public record Pending(UUID eventId,UUID accountId,String eventType,String topic,String payload,int attempts) {
        @Override public String toString() { return "OutboxPending["+eventId+"]"; }
    }
    private final JdbcTemplate jdbc;
    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Pending> next(String channel,Instant now) {
        return jdbc.query("""
                SELECT o.event_id,o.aggregate_id,o.event_type,o.topic,o.payload::text,o.attempt_count
                FROM outbox o
                WHERE o.channel=? AND o.published_at IS NULL AND o.available_at<=?
                  AND NOT EXISTS (
                    SELECT 1 FROM outbox earlier
                    WHERE earlier.aggregate_id=o.aggregate_id AND earlier.channel=o.channel AND earlier.published_at IS NULL
                      AND earlier.delivery_sequence<o.delivery_sequence)
                ORDER BY o.delivery_sequence LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """,(rs,row)->new Pending(rs.getObject("event_id",UUID.class),rs.getObject("aggregate_id",UUID.class),
                        rs.getString("event_type"),rs.getString("topic"),rs.getString("payload"),rs.getInt("attempt_count")),channel,Timestamp.from(now))
                .stream().findFirst();
    }
    public void published(UUID id,Instant now) {
        jdbc.update("UPDATE outbox SET published_at=? WHERE event_id=?",Timestamp.from(now),id);
    }
    public void retry(UUID id,Instant available) {
        jdbc.update("UPDATE outbox SET attempt_count=attempt_count+1,available_at=? WHERE event_id=?",Timestamp.from(available),id);
    }
}
