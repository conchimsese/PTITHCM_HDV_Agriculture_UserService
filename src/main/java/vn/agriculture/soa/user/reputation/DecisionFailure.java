package vn.agriculture.soa.user.reputation;

/** Safe machine code only; never embed input payloads or SQL in exception messages. */
public class DecisionFailure extends RuntimeException {
    private final boolean retryable;
    public DecisionFailure(String code,boolean retryable) { super(code); this.retryable=retryable; }
    public boolean retryable() { return retryable; }
    public static DecisionFailure invalid(String code) { return new DecisionFailure(code,false); }
    public static DecisionFailure retry(String code) { return new DecisionFailure(code,true); }
}
