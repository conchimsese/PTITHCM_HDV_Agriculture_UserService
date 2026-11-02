package vn.agriculture.soa.user.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.support.ProducerListener;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

@Configuration
@EnableScheduling
public class OutboxConfiguration {
    @Bean
    public ProducerListener<Object,Object> privateKafkaProducerListener() {
        return new ProducerListener<>() {
            @Override public void onError(ProducerRecord<Object,Object> record,RecordMetadata metadata,Exception exception) {
                // Invalid producer input may be on the technical DLT; never log its key/value or headers.
                org.slf4j.LoggerFactory.getLogger(OutboxConfiguration.class).warn("Kafka delivery deferred for topic {}",record.topic());
            }
        };
    }
}
