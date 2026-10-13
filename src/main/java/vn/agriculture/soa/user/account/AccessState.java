package vn.agriculture.soa.user.account;

import java.util.UUID;

public record AccessState(UUID accountId, AccountStatus status, AccountRole role, boolean accessAllowed) { }
