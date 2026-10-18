package vn.agriculture.soa.user.auth;

import org.springframework.http.HttpStatus;
import vn.agriculture.soa.user.error.ApiException;

public class RateLimitedException extends ApiException {
    private final long retryAfter;
    public RateLimitedException(long retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Try again later");
        this.retryAfter = Math.max(1, retryAfter);
    }
    public long retryAfter() { return retryAfter; }
}
