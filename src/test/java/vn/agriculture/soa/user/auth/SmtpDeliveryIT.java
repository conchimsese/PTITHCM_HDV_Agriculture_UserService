package vn.agriculture.soa.user.auth;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class SmtpDeliveryIT {
    @Test void realSmtpDeliveryAndTransportFailure() throws Exception {
        try (var server=new GenericContainer<>("axllent/mailpit:v1.27.10").withExposedPorts(1025,8025)
                .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025))) {
            server.start();
            var sender=new JavaMailSenderImpl(); sender.setHost(server.getHost()); sender.setPort(server.getMappedPort(1025));
            var properties=new Properties();
            properties.setProperty("mail.smtp.auth","false"); properties.setProperty("mail.smtp.starttls.enable","false");
            properties.setProperty("mail.smtp.connectiontimeout","1000"); properties.setProperty("mail.smtp.timeout","1000");
            sender.setJavaMailProperties(properties);
            var mailer=new SmtpOtpMailer(sender,"user-service@example.test");
            assertThat(mailer.send("recipient@example.test","123456")).isTrue();
            var client=HttpClient.newHttpClient(); String base="http://"+server.getHost()+":"+server.getMappedPort(8025);
            var list=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/messages")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var json=new ObjectMapper(); var messages=json.readTree(list.body()).get("messages");
            assertThat(messages.size()).isEqualTo(1);
            String id=messages.get(0).get("ID").asText();
            var message=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/message/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(message.body()).get("Text").asText()).contains("123456","10 minutes");
            server.stop();
            assertThat(mailer.send("recipient@example.test","654321")).isFalse();
        }
    }
}
