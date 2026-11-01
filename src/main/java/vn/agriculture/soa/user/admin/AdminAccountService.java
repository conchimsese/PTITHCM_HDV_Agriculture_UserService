package vn.agriculture.soa.user.admin;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.auth.AuthDtos.Register;
import vn.agriculture.soa.user.auth.AuthDtos.Registered;
import vn.agriculture.soa.user.auth.AuthService;
import vn.agriculture.soa.user.error.ApiException;
import vn.agriculture.soa.user.outbox.AccountOutboxWriter;
import vn.agriculture.soa.user.outbox.ProfileOutboxWriter;
import vn.agriculture.soa.user.profile.ProfileDtos;
import vn.agriculture.soa.user.profile.ProfileDtos.*;
import static vn.agriculture.soa.user.admin.AdminDtos.*;
import static vn.agriculture.soa.user.auth.AuthSupport.invalid;

@Service
@ConditionalOnProperty(name="user.auth.enabled", havingValue="true", matchIfMissing=true)
public class AdminAccountService {
    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);
    private static final String MEDIA_PREFIX = "/api/v1/users/media/";
    private static final Set<String> FARMER_FIELDS = Set.of("fullName", "phone", "address", "province", "district", "avatarUrl");
    private static final Set<String> TRADER_FIELDS = Set.of("businessName", "phone", "address", "province", "district", "avatarUrl", "buyingArea");

    private final AdminAccountRepository repository;
    private final AuthService authService;
    private final ProfileOutboxWriter profileOutbox;
    private final AccountOutboxWriter accountOutbox;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public AdminAccountService(
            AdminAccountRepository repository,
            AuthService authService,
            ProfileOutboxWriter profileOutbox,
            AccountOutboxWriter accountOutbox,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.authService = authService;
        this.profileOutbox = profileOutbox;
        this.accountOutbox = accountOutbox;
        this.clock = clock;
        this.writes = new TransactionTemplate(manager);
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Registered createAccount(UUID adminId, Register request) {
        return authService.registerByAdmin(request, adminId);
    }

    public PagedAccounts listAccounts(int page, int size, AccountRole role, AccountStatus status) {
        if (page < 0) { throw invalid("Page index cannot be negative"); }
        int effectiveSize = size <= 0 ? 20 : Math.min(size, 100);
        try {
            return reads.execute(txStatus -> repository.listAccounts(page, effectiveSize, role, status));
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    public AccountDetail getAccount(UUID accountId) {
        try {
            return reads.execute(txStatus -> findDetail(accountId, false));
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    public AccountDetail patchProfile(UUID adminId, UUID accountId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("Profile must be a JSON object");
        }
        try {
            return writes.execute(txStatus -> {
                lockAndCheckAdmin(adminId);
                AdminAccountRepository.AccountRecord account = repository.findAccount(accountId, true)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));
                if (account.role() == AccountRole.ADMIN) {
                    throw invalid("ADMIN has no editable Farmer/Trader profile");
                }

                Set<String> allowlist = account.role() == AccountRole.FARMER ? FARMER_FIELDS : TRADER_FIELDS;
                body.fieldNames().forEachRemaining(field -> {
                    if (!allowlist.contains(field)) {
                        throw invalid("Field outside the role allowlist: " + field);
                    }
                });

                Details before = repository.findProfileDetails(account)
                        .orElseThrow(AdminAccountService::unavailable);
                Details after = mergePatch(body, account.role(), before, accountId);

                if (!before.equals(after)) {
                    Instant now = clock.instant();
                    repository.updateProfileDetails(accountId, after, now);
                    boolean publicChanged = !ProfileDtos.snapshot(before, account.createdAt())
                            .equals(ProfileDtos.snapshot(after, account.createdAt()));
                    long newVersion = repository.touchAccount(accountId, publicChanged, now);
                    if (publicChanged) {
                        profileOutbox.append(accountId, account.role(), newVersion,
                                account.status() == AccountStatus.ACTIVE ? ProfileDtos.snapshot(after, account.createdAt()) : null, now, adminId);
                    }
                    log.info("SECURITY AUDIT: Admin [{}] patched profile for account [{}]", adminId, accountId);
                }
                return findDetail(accountId, false);
            });
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    public AccountDetail updateStatus(UUID adminId, UUID targetId, AccountStatus newStatus, String reason) {
        if (newStatus == null || newStatus == AccountStatus.UNVERIFIED) {
            throw invalid("Invalid status transition: UNVERIFIED is not allowed");
        }
        if (adminId.equals(targetId) && (newStatus == AccountStatus.LOCKED || newStatus == AccountStatus.INACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Administrator cannot lock or disable self");
        }

        try {
            return writes.execute(txStatus -> {
                lockAndCheckAdmin(adminId);
                AdminAccountRepository.AccountRecord target = repository.findAccount(targetId, true)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));

                if (target.role() == AccountRole.ADMIN && (newStatus == AccountStatus.LOCKED || newStatus == AccountStatus.INACTIVE)) {
                    int activeAdmins = repository.countActiveAdmins();
                    if (activeAdmins <= 1 && target.status() == AccountStatus.ACTIVE) {
                        throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Cannot lock or disable the last active administrator");
                    }
                }

                if (target.status() == newStatus) {
                    if (newStatus == AccountStatus.LOCKED && !target.adminLock()) {
                        repository.updateStatus(targetId, newStatus, true, clock.instant());
                        repository.revokeAllRefreshTokens(targetId, clock.instant());
                    }
                    return findDetail(targetId, false);
                }

                Instant now = clock.instant();
                if (newStatus == AccountStatus.LOCKED) {
                    repository.revokeAllRefreshTokens(targetId, now);
                    repository.updateStatus(targetId, AccountStatus.LOCKED, true, now);
                    accountOutbox.appendStatusChange(targetId, target.email(), target.emailVerifiedAt() != null, AccountStatus.LOCKED, reason, target.accountVersion() + 1, now, adminId);
                    if (target.role() != AccountRole.ADMIN && target.status() == AccountStatus.ACTIVE) {
                        long newProfileVersion = repository.touchAccount(targetId, true, now);
                        profileOutbox.append(targetId, target.role(), newProfileVersion, null, now, adminId);
                    }
                    log.info("SECURITY AUDIT: Admin [{}] locked account [{}]", adminId, targetId);
                } else if (newStatus == AccountStatus.INACTIVE) {
                    repository.revokeAllRefreshTokens(targetId, now);
                    repository.updateStatus(targetId, AccountStatus.INACTIVE, target.adminLock(), now);
                    if (target.role() != AccountRole.ADMIN && target.status() == AccountStatus.ACTIVE) {
                        long newProfileVersion = repository.touchAccount(targetId, true, now);
                        profileOutbox.append(targetId, target.role(), newProfileVersion, null, now, adminId);
                    }
                    log.info("SECURITY AUDIT: Admin [{}] disabled account [{}]", adminId, targetId);
                } else if (newStatus == AccountStatus.ACTIVE) {
                    if (target.emailVerifiedAt() == null) {
                        throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Cannot activate unverified account");
                    }
                    if (target.reputationLock()) {
                        throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Cannot restore account with active reputation lock");
                    }
                    if (target.role() != AccountRole.ADMIN) {
                        if (target.reputationScore() == null || target.reputationScore().compareTo(BigDecimal.ZERO) <= 0) {
                            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Cannot restore account with reputation score 0");
                        }
                    }
                    repository.updateStatus(targetId, AccountStatus.ACTIVE, false, now);
                    accountOutbox.appendStatusChange(targetId, target.email(), target.emailVerifiedAt() != null, AccountStatus.ACTIVE, reason, target.accountVersion() + 1, now, adminId);
                    if (target.role() != AccountRole.ADMIN && target.status() != AccountStatus.ACTIVE) {
                        Details profile = repository.findProfileDetails(target).orElseThrow(AdminAccountService::unavailable);
                        Snapshot snapshot = ProfileDtos.snapshot(profile, target.createdAt());
                        long newProfileVersion = repository.touchAccount(targetId, true, now);
                        profileOutbox.append(targetId, target.role(), newProfileVersion, snapshot, now, adminId);
                    }
                    log.info("SECURITY AUDIT: Admin [{}] unlocked account [{}]", adminId, targetId);
                }

                return findDetail(targetId, false);
            });
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    public void deleteAccount(UUID adminId, UUID targetId) {
        if (adminId.equals(targetId)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Administrator cannot delete self");
        }
        try {
            writes.executeWithoutResult(txStatus -> {
                lockAndCheckAdmin(adminId);
                AdminAccountRepository.AccountRecord target = repository.findAccount(targetId, true)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));

                if (target.role() == AccountRole.ADMIN && target.status() == AccountStatus.ACTIVE) {
                    int activeAdmins = repository.countActiveAdmins();
                    if (activeAdmins <= 1) {
                        throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Cannot delete the last active administrator");
                    }
                }

                if (target.status() != AccountStatus.INACTIVE) {
                    Instant now = clock.instant();
                    repository.revokeAllRefreshTokens(targetId, now);
                    repository.updateStatus(targetId, AccountStatus.INACTIVE, target.adminLock(), now);
                    if (target.role() != AccountRole.ADMIN && target.status() == AccountStatus.ACTIVE) {
                        long newProfileVersion = repository.touchAccount(targetId, true, now);
                        profileOutbox.append(targetId, target.role(), newProfileVersion, null, now, adminId);
                    }
                    log.info("SECURITY AUDIT: Admin [{}] soft-deleted account [{}]", adminId, targetId);
                }
            });
        } catch (DataAccessException | TransactionException ex) {
            throw unavailable();
        }
    }

    private void lockAndCheckAdmin(UUID adminId) {
        // All Admin mutations acquire the same transaction lock before any account row lock.
        repository.lockAdminMutations();
        var admin = repository.findAccount(adminId, true);
        if (admin.isEmpty() || admin.get().role() != AccountRole.ADMIN || admin.get().status() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "Administrator is no longer active");
        }
    }

    private Details mergePatch(JsonNode body, AccountRole role, Details before, UUID owner) {
        if (role == AccountRole.FARMER) {
            Farmer current = (Farmer) before;
            String fullName = body.has("fullName") ? text(body, "fullName", 150) : current.fullName();
            if (fullName == null || fullName.isBlank()) {
                throw invalid("Profile display name is required");
            }
            String phone = body.has("phone") ? text(body, "phone", 20) : current.phone();
            String address = body.has("address") ? text(body, "address", 255) : current.address();
            String province = body.has("province") ? text(body, "province", 100) : current.province();
            String district = body.has("district") ? text(body, "district", 100) : current.district();
            String avatarUrl = body.has("avatarUrl") ? text(body, "avatarUrl", 500) : current.avatarUrl();
            if (avatarUrl != null) {
                validateAvatar(avatarUrl, owner);
            }
            return new Farmer(fullName.trim(), phone, address, province, district, avatarUrl);
        } else {
            Trader current = (Trader) before;
            String businessName = body.has("businessName") ? text(body, "businessName", 200) : current.businessName();
            if (businessName == null || businessName.isBlank()) {
                throw invalid("Profile display name is required");
            }
            String phone = body.has("phone") ? text(body, "phone", 20) : current.phone();
            String address = body.has("address") ? text(body, "address", 255) : current.address();
            String province = body.has("province") ? text(body, "province", 100) : current.province();
            String district = body.has("district") ? text(body, "district", 100) : current.district();
            String avatarUrl = body.has("avatarUrl") ? text(body, "avatarUrl", 500) : current.avatarUrl();
            if (avatarUrl != null) {
                validateAvatar(avatarUrl, owner);
            }
            String buyingArea = body.has("buyingArea") ? text(body, "buyingArea", 255) : current.buyingArea();
            return new Trader(businessName.trim(), phone, address, province, district, avatarUrl, buyingArea);
        }
    }

    private void validateAvatar(String avatar, UUID owner) {
        if (!avatar.startsWith(MEDIA_PREFIX)) {
            throw invalid("avatarUrl must reference attached User media owned by this account");
        }
        String suffix = avatar.substring(MEDIA_PREFIX.length());
        try {
            UUID mediaId = UUID.fromString(suffix);
            if (!mediaId.toString().equals(suffix) || !repository.ownsAttachedAvatar(mediaId, owner)) {
                throw invalid("avatarUrl must reference attached User media owned by this account");
            }
        } catch (IllegalArgumentException ex) {
            throw invalid("avatarUrl must reference attached User media owned by this account");
        }
    }

    private AccountDetail findDetail(UUID accountId, boolean lock) {
        AdminAccountRepository.AccountRecord acc = repository.findAccount(accountId, lock)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));
        Details profile = repository.findProfileDetails(acc).orElse(null);
        return new AccountDetail(
                acc.id(), acc.username(), acc.email(), acc.role(), acc.status(),
                acc.createdAt(), acc.emailVerifiedAt(), profile
        );
    }

    private static String text(JsonNode body, String field, int maximum) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) { return null; }
        if (!value.isTextual() || value.textValue().length() > maximum) {
            throw invalid("Invalid profile field type or length");
        }
        return value.textValue();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Account storage is temporarily unavailable");
    }
}
