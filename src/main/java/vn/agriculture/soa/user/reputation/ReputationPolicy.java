package vn.agriculture.soa.user.reputation;

import java.math.BigDecimal;
import java.util.Map;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.reputation.ReputationDecision.*;

public final class ReputationPolicy {
    private ReputationPolicy() { }
    private static final Map<String,Integer> PENALTIES=Map.ofEntries(
            Map.entry("F01",1),Map.entry("F02",2),Map.entry("F03",2),Map.entry("F04",2),Map.entry("F05",2),Map.entry("F06",3),Map.entry("F07",3),Map.entry("F08",5),
            Map.entry("T01",1),Map.entry("T02",2),Map.entry("T03",2),Map.entry("T04",3),Map.entry("T05",3),Map.entry("T06",3),Map.entry("T07",5),Map.entry("T08",2));
    public static void validate(AccountRole role,Data data) {
        if (role==AccountRole.ADMIN) { throw DecisionFailure.invalid("REPUTATION_ROLE_NOT_SUPPORTED"); }
        if (data.decisionVersion()!=(data.action()==Action.REVERSAL?2:1)) {
            throw DecisionFailure.invalid("INVALID_DECISION_VERSION");
        }
        if (data.action()!=Action.REVERSAL && data.originalDecisionId()!=null) {
            throw DecisionFailure.invalid("INVALID_BASE_DECISION_REFERENCE");
        }
        if (data.action()==Action.PENALTY) {
            Integer penalty=data.violationCode()==null?null:PENALTIES.get(data.violationCode());
            String prefix=role==AccountRole.FARMER?"F":"T";
            if (penalty==null || !data.violationCode().startsWith(prefix)
                    || !(data.requestedDelta().compareTo(BigDecimal.valueOf(-penalty))==0
                        || data.violationCode().equals("F04") && data.requestedDelta().compareTo(BigDecimal.valueOf(-3))==0)) {
                throw DecisionFailure.invalid("INVALID_PENALTY_POLICY");
            }
        } else if (data.action()==Action.COMPLETION_REWARD) {
            if (data.violationCode()!=null || data.requestedDelta().compareTo(new BigDecimal("0.5"))!=0) {
                throw DecisionFailure.invalid("INVALID_REWARD_POLICY");
            }
        } else if (data.originalDecisionId()==null) {
            throw DecisionFailure.invalid("REVERSAL_REFERENCE_REQUIRED");
        }
    }
    public static BigDecimal clamp(BigDecimal value) { return value.max(BigDecimal.ZERO).min(BigDecimal.TEN); }
}
