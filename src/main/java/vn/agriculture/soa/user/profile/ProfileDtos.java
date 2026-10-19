package vn.agriculture.soa.user.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;

public final class ProfileDtos {
    private ProfileDtos() { }

    public sealed interface Details permits Farmer, Trader { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Farmer(String fullName, String phone, String address, String province,
                         String district, String avatarUrl) implements Details {
        @Override public String toString() { return "FarmerProfile[redacted]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Trader(String businessName, String phone, String address, String province,
                         String district, String avatarUrl, String buyingArea) implements Details {
        @Override public String toString() { return "TraderProfile[redacted]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PrivateProfile(UUID accountId, String username, String email, AccountRole role,
                                 AccountStatus status, Instant createdAt, Instant emailVerifiedAt, Details profile) {
        @Override public String toString() { return "PrivateProfile[redacted]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PublicProfile(UUID accountId, String displayName, String avatarUrl,
                                String province, String district, Instant joinedAt) { }

    /** The public replica has no contacts, account status or reputation. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(String displayName, String avatarUrl, String province, String district, Instant joinedAt) { }

    public static Snapshot snapshot(Details profile, Instant joinedAt) {
        return switch (profile) {
            case Farmer farmer -> new Snapshot(farmer.fullName(), farmer.avatarUrl(), farmer.province(), farmer.district(), joinedAt);
            case Trader trader -> new Snapshot(trader.businessName(), trader.avatarUrl(), trader.province(), trader.district(), joinedAt);
        };
    }
}
