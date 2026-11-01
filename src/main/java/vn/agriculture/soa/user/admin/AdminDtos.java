package vn.agriculture.soa.user.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.profile.ProfileDtos.Details;

public final class AdminDtos {
    private AdminDtos() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccountSummary(UUID accountId, String username, String email, AccountRole role,
                                 AccountStatus status, Instant createdAt) {
        @Override public String toString() { return "AccountSummary[redacted]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccountDetail(UUID accountId, String username, String email, AccountRole role,
                                AccountStatus status, Instant createdAt, Instant emailVerifiedAt,
                                Details profile) {
        @Override public String toString() { return "AccountDetail[redacted]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PagedAccounts(List<AccountSummary> items, int page, int size,
                               long totalElements, int totalPages) { }

    public record UpdateStatusRequest(@NotNull AccountStatus status, String reason) { }
}
