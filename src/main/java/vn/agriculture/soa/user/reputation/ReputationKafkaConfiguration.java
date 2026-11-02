package vn.agriculture.soa.user.reputation;

import java.util.HashMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name="user.reputation.enabled",havingValue="true")
public class ReputationKafkaConfiguration {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String,String> reputationKafkaListenerContainerFactory(
            KafkaProperties properties,DecisionDeadLetterPublisher dlt,@Value("${user.reputation.retry-ms:5000}") long retryMs,
            @Value("${user.reputation.invalid-retry-ms:1000}") long invalidRetryMs,@Value("${user.reputation.concurrency:1}") int concurrency) {
        if (retryMs<1 || invalidRetryMs<1 || concurrency<1) { throw new IllegalArgumentException("Invalid reputation listener settings"); }
        var consumer=new HashMap<String,Object>(properties.buildConsumerProperties());
        consumer.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        consumer.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        consumer.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1);
        var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true); factory.setConcurrency(concurrency);
        var handler=new DefaultErrorHandler(dlt::send,new FixedBackOff(invalidRetryMs,2));
        handler.setBackOffFunction((record,exception)->DecisionDeadLetterPublisher.retryable(exception)?
                new FixedBackOff(retryMs,FixedBackOff.UNLIMITED_ATTEMPTS):new FixedBackOff(invalidRetryMs,2));
        handler.setCommitRecovered(true); handler.setLogLevel(KafkaException.Level.DEBUG);
        factory.setCommonErrorHandler(handler); return factory;
    }
}
