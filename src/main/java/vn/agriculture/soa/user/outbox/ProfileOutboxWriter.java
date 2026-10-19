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
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.profile.ProfileDtos.Snapshot;

/** Writes an immutable event intent only; Kafka relay belongs to Phase 5. */
@Component
public class ProfileOutboxWriter {
    public static final String TOPIC = "soa.user.profile.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ProfileOutboxWriter(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    private record Data(UUID accountId,AccountRole role,long profileVersion,boolean publicVisible,Snapshot profile) { }
    private record Envelope(UUID eventId,String eventType,int eventVersion,String sourceService,Instant occurredAt,
                            UUID actorId,List<UUID> recipientIds,String referenceType,UUID referenceId,Data data) { }

    public void append(UUID accountId,AccountRole role,long version,Snapshot profile,Instant now,UUID actorId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Profile outbox requires the business transaction");
        }
        if (role == AccountRole.ADMIN || version < 1) { throw new IllegalArgumentException("Only versioned Farmer/Trader profiles are public"); }
        UUID eventId = UUID.randomUUID();
        var envelope = new Envelope(eventId,"USER_PROFILE_UPDATED",1,"user-service",now,actorId,List.of(),
                "ACCOUNT",accountId,new Data(accountId,role,version,profile != null,profile));
        try {
            String payload = json.writeValueAsString(envelope);
            jdbc.update("""
                    INSERT INTO outbox(event_id,aggregate_id,channel,event_type,topic,payload,created_at,available_at)
                    VALUES (?,?,'KAFKA','USER_PROFILE_UPDATED',?,?::jsonb,?,?)
                    """, eventId,accountId,TOPIC,payload,Timestamp.from(now),Timestamp.from(now));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize public profile event");
        }
    }
}
