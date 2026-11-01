package vn.agriculture.soa.user.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Integration contract fixture, not a claim about the implementation in Trading. */
public class ProfileReplica {
    public record State(long version,boolean visible,JsonNode profile) { }
    private final Map<UUID,State> accounts=new HashMap<>();
    public void apply(String payload) throws Exception {
        JsonNode event=new ObjectMapper().readTree(payload),data=event.get("data");
        if (!event.path("eventType").asText().equals("USER_PROFILE_UPDATED")
                || !event.path("sourceService").asText().equals("user-service") || event.path("eventVersion").asInt()!=1) {
            throw new IllegalArgumentException("Unsupported profile envelope");
        }
        UUID account=UUID.fromString(data.get("accountId").asText()); long version=data.get("profileVersion").asLong();
        State before=accounts.get(account);
        if (before==null || before.version()<version) {
            accounts.put(account,new State(version,data.get("publicVisible").asBoolean(),data.get("profile")));
        }
    }
    public State get(UUID account) { return accounts.get(account); }
}
