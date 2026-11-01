package vn.agriculture.soa.user.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.agriculture.soa.user.account.AccountStatus;

@Component
public class AccountOutboxWriter {
    public static final String TOPIC = "soa.user.account.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AccountOutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private record Data(UUID accountId, String reasonCode, long accountVersion) { }
    private record Envelope(UUID eventId, String eventType, int eventVersion, String sourceService, Instant occurredAt,
                            UUID actorId, List<UUID> recipientIds, String referenceType, UUID referenceId, Data data) { }
    private record EmailPayload(UUID accountId, String email, String eventType, String reason, Instant occurredAt) { }

    public void appendStatusChange(UUID accountId, String email, boolean emailVerified, AccountStatus status, String reason,
                                   long accountVersion, Instant now, UUID actorId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Account outbox requires the business transaction");
        }
        String eventType = status == AccountStatus.LOCKED ? "ACCOUNT_LOCKED" : "ACCOUNT_UNLOCKED";
        UUID eventId = UUID.randomUUID();
        var envelope = new Envelope(eventId, eventType, 1, "user-service", now, actorId,
                List.of(accountId), "ACCOUNT", accountId,
                new Data(accountId, status == AccountStatus.LOCKED ? "ADMIN_LOCK" : "ADMIN_UNLOCK", accountVersion));
        var emailPayload = new EmailPayload(accountId, email, eventType, reason, now);
        try {
            String kafkaPayload = json.writeValueAsString(envelope);
            jdbc.update("""
                    INSERT INTO outbox(event_id, aggregate_id, channel, event_type, topic, payload, created_at, available_at)
                    VALUES (?, ?, 'KAFKA', ?, ?, ?::jsonb, ?, ?)
                    """, eventId, accountId, eventType, TOPIC, kafkaPayload, Timestamp.from(now), Timestamp.from(now));

            if (!emailVerified) { return; }
            UUID emailEventId = UUID.randomUUID();
            String emailJson = json.writeValueAsString(emailPayload);
            jdbc.update("""
                    INSERT INTO outbox(event_id, aggregate_id, channel, event_type, topic, payload, created_at, available_at)
                    VALUES (?, ?, 'EMAIL', ?, NULL, ?::jsonb, ?, ?)
                    """, emailEventId, accountId, eventType, emailJson, Timestamp.from(now), Timestamp.from(now));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize account outbox event", ex);
        }
    }
}
