package vn.agriculture.soa.user.outbox;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOutboxDelivery {
    private final KafkaTemplate<String,String> kafka;
    public KafkaOutboxDelivery(KafkaTemplate<String,String> kafka) { this.kafka=kafka; }
    public void send(OutboxRepository.Pending event) throws Exception {
        if (!Set.of("soa.user.profile.v1","soa.user.account.v1","soa.user.reputation.v1").contains(event.topic())) {
            throw new IllegalArgumentException("Unsupported business topic");
        }
        // Success means broker acknowledgement; a timeout may still have delivered the record.
        kafka.send(event.topic(),event.accountId().toString(),event.payload()).get(6,TimeUnit.SECONDS);
    }
}
