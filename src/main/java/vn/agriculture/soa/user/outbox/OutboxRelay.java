package vn.agriculture.soa.user.outbox;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxRelay {
    private static final Logger log=LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository repository;
    private final KafkaOutboxDelivery kafka;
    private final AccountStatusMailer mail;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final boolean kafkaEnabled;
    private final boolean emailEnabled;
    public OutboxRelay(OutboxRepository repository,KafkaOutboxDelivery kafka,AccountStatusMailer mail,Clock clock,
                       PlatformTransactionManager manager,@Value("${user.outbox.kafka-enabled:false}") boolean kafkaEnabled,
                       @Value("${user.outbox.email-enabled:false}") boolean emailEnabled) {
        this.repository=repository; this.kafka=kafka; this.mail=mail; this.clock=clock;
        tx=new TransactionTemplate(manager); this.kafkaEnabled=kafkaEnabled; this.emailEnabled=emailEnabled;
    }
    @Scheduled(fixedDelayString="${user.outbox.poll-ms:1000}",initialDelayString="${user.outbox.initial-delay-ms:5000}")
    public void pollKafka() { if (kafkaEnabled) { poll("KAFKA"); } }
    @Scheduled(fixedDelayString="${user.outbox.poll-ms:1000}",initialDelayString="${user.outbox.initial-delay-ms:5000}")
    public void pollEmail() { if (emailEnabled) { poll("EMAIL"); } }
    private void poll(String channel) {
        try { for (int i=0;i<20 && processOne(channel);i++) { /* Commit each delivery independently. */ } }
        catch (RuntimeException ex) { log.warn("Outbox {} poll deferred; transaction unavailable",channel); }
    }
    public boolean processOne(String channel) {
        if (!channel.equals("KAFKA") && !channel.equals("EMAIL")) { throw new IllegalArgumentException("Unsupported channel"); }
        return Boolean.TRUE.equals(tx.execute(status->{
            var next=repository.next(channel,clock.instant());
            if (next.isEmpty()) { return false; }
            var event=next.get();
            try {
                if (channel.equals("KAFKA")) { kafka.send(event); } else { mail.send(event); }
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                long delay=Math.min(3600L,5L << Math.min(10,event.attempts()));
                repository.retry(event.eventId(),clock.instant().plusSeconds(delay));
                log.warn("Outbox {} delivery deferred for event {}",channel,event.eventId());
                return true;
            }
            // Any crash/DB failure here rolls back the marker; retry may duplicate the acknowledged event.
            repository.published(event.eventId(),clock.instant());
            return true;
        }));
    }
}
