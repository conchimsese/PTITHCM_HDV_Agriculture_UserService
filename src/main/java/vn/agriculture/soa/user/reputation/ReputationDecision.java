package vn.agriculture.soa.user.reputation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

public record ReputationDecision(UUID eventId,Instant occurredAt,UUID actorId,List<UUID> recipientIds,
                                 String referenceType,UUID referenceId,UUID correlationId,Data data) {
    public enum Action { PENALTY, COMPLETION_REWARD, REVERSAL }
    public record Data(UUID decisionId,long decisionVersion,UUID originalDecisionId,UUID contractId,UUID accountId,
                       UUID counterpartyId,Action action,String violationCode,BigDecimal requestedDelta,String reasonCode,Instant effectiveAt) { }
    @Override public String toString() { return "ReputationDecision["+eventId+"]"; }
    @Component
    public static class Parser {
        private final ObjectMapper json;
        private static final Set<String> ROOT=Set.of("eventId","eventType","eventVersion","sourceService","occurredAt","actorId","recipientIds","referenceType","referenceId","correlationId","data");
        private static final Set<String> DATA=Set.of("decisionId","decisionVersion","originalDecisionId","contractId","accountId","counterpartyId","action","violationCode","requestedDelta","reasonCode","effectiveAt");
        public Parser(ObjectMapper json) { this.json=json; }
        public ReputationDecision parse(String key,String payload) {
            try {
                if (payload==null || payload.getBytes(StandardCharsets.UTF_8).length>65536) { throw invalid(); }
                JsonNode root=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(payload); object(root,ROOT);
                if (!text(root,"eventType").equals("CONTRACT_REPUTATION_DECIDED") || number(root,"eventVersion")!=1
                        || !text(root,"sourceService").equals("payment-service")) { throw invalid(); }
                JsonNode d=root.get("data"); object(d,DATA);
                UUID account=uuid(d,"accountId",false),counterparty=uuid(d,"counterpartyId",false);
                if (account.equals(counterparty) || key==null || !account.equals(canonical(key))) { throw invalid(); }
                if (!d.path("requestedDelta").isNumber()) { throw invalid(); }
                BigDecimal delta=d.get("requestedDelta").decimalValue().stripTrailingZeros();
                if (delta.abs().compareTo(BigDecimal.TEN)>0 || delta.scale()>1 || delta.remainder(new BigDecimal("0.5")).signum()!=0) { throw invalid(); }
                var data=new Data(uuid(d,"decisionId",false),number(d,"decisionVersion"),uuid(d,"originalDecisionId",true),uuid(d,"contractId",false),
                        account,counterparty,Action.valueOf(text(d,"action")),code(d,"violationCode",true),delta,code(d,"reasonCode",false),Instant.parse(text(d,"effectiveAt")));
                if (data.decisionVersion()<1) { throw invalid(); }
                String reference=text(root,"referenceType"); UUID referenceId=uuid(root,"referenceId",false);
                if (!(reference.equals("CONTRACT") && referenceId.equals(data.contractId()))
                        && !(reference.equals("ACCOUNT") && referenceId.equals(account))) { throw invalid(); }
                JsonNode recipients=root.get("recipientIds"); if (recipients==null || !recipients.isArray() || recipients.size()>2) { throw invalid(); }
                var ids=new ArrayList<UUID>();
                for (JsonNode recipient:recipients) {
                    if (!recipient.isTextual()) { throw invalid(); }
                    UUID id=canonical(recipient.asText()); if (ids.contains(id)) { throw invalid(); } ids.add(id);
                }
                return new ReputationDecision(uuid(root,"eventId",false),Instant.parse(text(root,"occurredAt")),uuid(root,"actorId",true),List.copyOf(ids),
                        reference,referenceId,uuid(root,"correlationId",true),data);
            } catch (DecisionFailure ex) { throw ex; }
            catch (Exception ex) { throw invalid(); }
        }
        public String fingerprint(Object value) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
            catch (Exception ex) { throw new IllegalStateException("Decision fingerprint unavailable"); }
        }
        public String dataJson(Data data) {
            try { return json.writeValueAsString(data); } catch (Exception ex) { throw new IllegalStateException("Decision encoding unavailable"); }
        }
        private static void object(JsonNode value,Set<String> fields) {
            if (value==null || !value.isObject()) { throw invalid(); }
            value.fieldNames().forEachRemaining(name->{if (!fields.contains(name)) { throw invalid(); }});
        }
        private static String text(JsonNode node,String field) {
            JsonNode value=node.get(field); if (value==null || !value.isTextual() || value.asText().isBlank()) { throw invalid(); } return value.asText();
        }
        private static long number(JsonNode node,String field) {
            JsonNode value=node.get(field); if (value==null || !value.isIntegralNumber() || !value.canConvertToLong()) { throw invalid(); } return value.longValue();
        }
        private static UUID uuid(JsonNode node,String field,boolean optional) {
            if (optional && (!node.hasNonNull(field))) { return null; } return canonical(text(node,field));
        }
        private static UUID canonical(String value) {
            UUID id=UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) { throw invalid(); } return id;
        }
        private static String code(JsonNode node,String field,boolean optional) {
            if (optional && !node.hasNonNull(field)) { return null; }
            String code=text(node,field); if (!code.matches("[A-Z][A-Z0-9_]{0,99}")) { throw invalid(); } return code;
        }
        private static DecisionFailure invalid() { return DecisionFailure.invalid("INVALID_DECISION_ENVELOPE"); }
    }
}
