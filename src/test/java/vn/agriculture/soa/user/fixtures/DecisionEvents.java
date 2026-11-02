package vn.agriculture.soa.user.fixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class DecisionEvents {
    private DecisionEvents() { }
    public static ObjectNode event(UUID account,UUID counterparty,UUID contract,UUID decision,String action,String code,BigDecimal delta) {
        var json=new ObjectMapper(); var root=json.createObjectNode();
        root.put("eventId",UUID.randomUUID().toString()).put("eventType","CONTRACT_REPUTATION_DECIDED").put("eventVersion",1)
                .put("sourceService","payment-service").put("occurredAt",Instant.parse("2026-11-01T00:00:00Z").toString());
        root.putNull("actorId"); root.putArray("recipientIds");
        root.put("referenceType","CONTRACT").put("referenceId",contract.toString());
        root.putObject("data").put("accountId",account.toString()).put("counterpartyId",counterparty.toString()).put("contractId",contract.toString())
                .put("decisionId",decision.toString()).put("decisionVersion",1).put("action",action).put("violationCode",code)
                .put("requestedDelta",delta).put("reasonCode","CONFIRMED_DECISION").put("effectiveAt","2026-11-01T00:00:00Z");
        return root;
    }
    public static ObjectNode penalty(UUID account,UUID counterparty,String code,int delta) {
        return event(account,counterparty,UUID.randomUUID(),UUID.randomUUID(),"PENALTY",code,BigDecimal.valueOf(delta));
    }
}
