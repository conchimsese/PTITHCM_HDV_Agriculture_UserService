package vn.agriculture.soa.user.reputation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.fixtures.DecisionEvents;

class ReputationValidationTest {
    final ReputationDecision.Parser parser=new ReputationDecision.Parser(new ObjectMapper());
    final UUID account=UUID.randomUUID(),other=UUID.randomUUID();
    private ObjectNode event() { return DecisionEvents.penalty(account,other,"F01",-1); }
    @Test void acceptsCanonicalEnvelopeAndBothReferenceBindings() {
        var root=event(); assertThat(parser.parse(account.toString(),root.toString()).data().requestedDelta()).isEqualByComparingTo("-1");
        root.put("referenceType","ACCOUNT").put("referenceId",account.toString());
        assertThatCode(()->parser.parse(account.toString(),root.toString())).doesNotThrowAnyException();
    }
    @Test void rejectsWrongSourceKeyReferenceAndUnknownPrivateField() {
        for (String field:new String[] {"sourceService","key","referenceId","privateField"}) {
            var root=event(); String key=account.toString();
            if (field.equals("sourceService")) { root.put(field,"notification-service"); }
            if (field.equals("key")) { key=other.toString(); }
            if (field.equals("referenceId")) { root.put(field,UUID.randomUUID().toString()); }
            if (field.equals("privateField")) { ((ObjectNode)root.get("data")).put("email","private@example.test"); }
            String selectedKey=key;
            assertThatThrownBy(()->parser.parse(selectedKey,root.toString())).isInstanceOf(DecisionFailure.class).hasMessage("INVALID_DECISION_ENVELOPE");
        }
    }
    @Test void rejectsDuplicateJsonKeysInvalidNumbersAndMalformedPayloads() {
        assertThatThrownBy(()->parser.parse(account.toString(),"{\"eventVersion\":1,\"eventVersion\":2}" )).isInstanceOf(DecisionFailure.class);
        assertThatThrownBy(()->parser.parse(account.toString(),"{" )).isInstanceOf(DecisionFailure.class);
        for (String delta:new String[] {"0.25","1000000000000","-10.5"}) {
            var root=event(); ((ObjectNode)root.get("data")).put("requestedDelta",new BigDecimal(delta));
            assertThatThrownBy(()->parser.parse(account.toString(),root.toString())).isInstanceOf(DecisionFailure.class);
        }
        var root=event(); ((ObjectNode)root.get("data")).put("decisionVersion",1.5);
        assertThatThrownBy(()->parser.parse(account.toString(),root.toString())).isInstanceOf(DecisionFailure.class);
    }
    @Test void allPenaltyCodesAndFarmerF04AlternativesMatchTheBaPolicy() {
        String[] codes={"F01","F02","F03","F04","F05","F06","F07","F08","T01","T02","T03","T04","T05","T06","T07","T08"};
        int[] deltas={-1,-2,-2,-2,-2,-3,-3,-5,-1,-2,-2,-3,-3,-3,-5,-2};
        for (int i=0;i<codes.length;i++) {
            var root=DecisionEvents.penalty(account,other,codes[i],deltas[i]);
            var d=parser.parse(account.toString(),root.toString()).data();
            AccountRole role=codes[i].startsWith("F")?AccountRole.FARMER:AccountRole.TRADER;
            assertThatCode(()->ReputationPolicy.validate(role,d)).doesNotThrowAnyException();
        }
        var f04=parser.parse(account.toString(),DecisionEvents.penalty(account,other,"F04",-3).toString()).data();
        assertThatCode(()->ReputationPolicy.validate(AccountRole.FARMER,f04)).doesNotThrowAnyException();
    }
    @Test void rejectsWrongRoleArbitraryPenaltyAndRewardMagnitude() {
        var farmer=parser.parse(account.toString(),event().toString()).data();
        assertThatThrownBy(()->ReputationPolicy.validate(AccountRole.TRADER,farmer)).isInstanceOf(DecisionFailure.class);
        assertThatThrownBy(()->ReputationPolicy.validate(AccountRole.ADMIN,farmer)).isInstanceOf(DecisionFailure.class);
        var arbitrary=parser.parse(account.toString(),DecisionEvents.penalty(account,other,"F01",-2).toString()).data();
        assertThatThrownBy(()->ReputationPolicy.validate(AccountRole.FARMER,arbitrary)).isInstanceOf(DecisionFailure.class);
        var reward=parser.parse(account.toString(),DecisionEvents.event(account,other,UUID.randomUUID(),UUID.randomUUID(),"COMPLETION_REWARD",null,BigDecimal.ONE).toString()).data();
        assertThatThrownBy(()->ReputationPolicy.validate(AccountRole.FARMER,reward)).isInstanceOf(DecisionFailure.class);
    }
    @Test void baseVersionIsOneAndAllReversalsRequireVersionTwo() {
        var base=event(); ((ObjectNode)base.get("data")).put("decisionVersion",2);
        assertThatThrownBy(()->ReputationPolicy.validate(AccountRole.FARMER,parser.parse(account.toString(),base.toString()).data())).hasMessage("INVALID_DECISION_VERSION");
        var reverse=DecisionEvents.event(account,other,UUID.randomUUID(),UUID.randomUUID(),"REVERSAL",null,BigDecimal.TEN);
        ((ObjectNode)reverse.get("data")).put("originalDecisionId",UUID.randomUUID().toString());
        assertThatThrownBy(()->ReputationPolicy.validate(AccountRole.FARMER,parser.parse(account.toString(),reverse.toString()).data())).hasMessage("INVALID_DECISION_VERSION");
        ((ObjectNode)reverse.get("data")).put("decisionVersion",2);
        assertThatCode(()->ReputationPolicy.validate(AccountRole.FARMER,parser.parse(account.toString(),reverse.toString()).data())).doesNotThrowAnyException();
    }
    @Test void documentedPaymentFixturesMatchTheUserContract() throws Exception {
        for (String name:new String[]{"penalty","reward","reversal-penalty","reversal-reward"}) {
            String payload=java.nio.file.Files.readString(java.nio.file.Path.of("docs/fixtures/reputation-"+name+".json"));
            String key=new ObjectMapper().readTree(payload).get("data").get("accountId").asText();
            var decision=parser.parse(key,payload);
            assertThatCode(()->ReputationPolicy.validate(AccountRole.FARMER,decision.data())).doesNotThrowAnyException();
        }
    }
}
