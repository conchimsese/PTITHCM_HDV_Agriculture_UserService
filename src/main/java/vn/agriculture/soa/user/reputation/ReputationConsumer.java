package vn.agriculture.soa.user.reputation;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="user.reputation.enabled",havingValue="true")
public class ReputationConsumer {
    public static final String TOPIC="soa.payment.reputation-decision.v1";
    public static final String GROUP="user-reputation-decision-v1";
    private final ReputationService service;
    public ReputationConsumer(ReputationService service) { this.service=service; }
    @KafkaListener(id="user-reputation-decisions",topics=TOPIC,groupId=GROUP,containerFactory="reputationKafkaListenerContainerFactory",autoStartup="true")
    public void consume(ConsumerRecord<String,String> record,Acknowledgment acknowledgment) {
        service.apply(record.key(),record.value()); // Returns only after the business transaction has committed.
        acknowledgment.acknowledge();
    }
}
