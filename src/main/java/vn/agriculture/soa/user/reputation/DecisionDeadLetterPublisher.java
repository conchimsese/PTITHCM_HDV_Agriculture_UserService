package vn.agriculture.soa.user.reputation;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class DecisionDeadLetterPublisher {
    public static final String TOPIC="soa.user.reputation-decision.dlt.v1";
    private final KafkaTemplate<String,String> kafka;
    public DecisionDeadLetterPublisher(KafkaTemplate<String,String> kafka) { this.kafka=kafka; }
    public void send(ConsumerRecord<?,?> source,Exception exception) {
        var record=new ProducerRecord<String,String>(TOPIC,(String)source.key(),(String)source.value());
        record.headers().add("user-original-topic",source.topic().getBytes(StandardCharsets.UTF_8));
        record.headers().add("user-original-partition",Integer.toString(source.partition()).getBytes(StandardCharsets.US_ASCII));
        record.headers().add("user-original-offset",Long.toString(source.offset()).getBytes(StandardCharsets.US_ASCII));
        record.headers().add("user-error-code",code(exception).getBytes(StandardCharsets.US_ASCII));
        try { kafka.send(record).get(6,TimeUnit.SECONDS); }
        catch (Exception ex) {
            if (ex instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("DECISION_DLT_FORWARD_FAILED");
        }
    }
    static String code(Throwable failure) {
        for (Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if (cause instanceof DecisionFailure decision) { return decision.getMessage(); }
        }
        return "DECISION_PROCESSING_FAILURE";
    }
    static boolean retryable(Throwable failure) {
        for (Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if (cause instanceof DecisionFailure decision) { return decision.retryable(); }
            if (cause instanceof org.springframework.dao.DataAccessException || cause instanceof org.springframework.transaction.TransactionException) { return true; }
        }
        return true; // Unexpected infrastructure failures must not silently skip a valid decision.
    }
}
