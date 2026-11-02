package vn.agriculture.soa.user.reputation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Operator-only replay of approved Payment envelopes; no new HTTP endpoint or fabricated decisions. */
@Component
public class ReputationReplay implements ApplicationRunner {
    private final ReputationService service;
    private final ReputationDecision.Parser parser;
    private final boolean enabled;
    private final String file;
    public ReputationReplay(ReputationService service,ReputationDecision.Parser parser,
                            @Value("${user.reputation.replay-enabled:false}") boolean enabled,
                            @Value("${user.reputation.replay-file:}") String file) {
        this.service=service; this.parser=parser; this.enabled=enabled; this.file=file;
    }
    @Override public void run(ApplicationArguments args) throws IOException { if (enabled) { replay(Path.of(file)); } }
    public void replay(Path path) throws IOException {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>64L*1024*1024) { throw new IllegalArgumentException("Invalid replay file"); }
        try (var lines=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            String line; int count=0;
            while ((line=lines.readLine())!=null) {
                if (line.isBlank()) { continue; }
                if (++count>1000) { throw new IllegalArgumentException("Replay batch exceeds 1000 records"); }
                String key;
                try { key=new com.fasterxml.jackson.databind.ObjectMapper().readTree(line).path("data").path("accountId").asText(); }
                catch (Exception ex) { throw DecisionFailure.invalid("INVALID_REPLAY_ENVELOPE"); }
                service.apply(parser.parse(key,line));
            }
        }
    }
}
