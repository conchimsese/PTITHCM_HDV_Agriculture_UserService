package vn.agriculture.soa.user.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.error.ApiException;
import vn.agriculture.soa.user.outbox.ProfileOutboxWriter;
import static vn.agriculture.soa.user.profile.ProfileDtos.*;

@Service
public class ProfileService {
    private final ProfileRepository repository;
    private final ProfileOutboxWriter outbox;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public ProfileService(ProfileRepository repository,ProfileOutboxWriter outbox,Clock clock,PlatformTransactionManager manager) {
        this.repository=repository; this.outbox=outbox; this.clock=clock;
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public PrivateProfile me(UUID caller) {
        try {
            return reads.execute(status -> {
                var account = active(caller,false);
                return response(account,details(account));
            });
        } catch (DataAccessException | TransactionException ex) { throw unavailable(); }
    }

    public PublicProfile publicProfile(UUID target,AccountRole expectedRole) {
        try {
            return repository.publicProfile(target,expectedRole).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Profile not found"));
        } catch (DataAccessException ex) { throw unavailable(); }
    }

    public PrivateProfile put(UUID caller,JsonNode body) {
        try {
            return writes.execute(status -> {
                // Read the profile AFTER acquiring the account lock, using a fresh statement snapshot.
                var account = active(caller,true);
                if (account.role() == AccountRole.ADMIN) { throw vn.agriculture.soa.user.auth.AuthSupport.invalid("ADMIN has no editable Farmer/Trader profile"); }
                Details before = details(account);
                Details after = ProfileUpdate.parse(body,account.role(),caller,repository);
                if (!before.equals(after)) {
                    var now = clock.instant();
                    boolean publicChanged = !snapshot(before,account.createdAt()).equals(snapshot(after,account.createdAt()));
                    if (repository.update(caller,after,now) != 1) { throw unavailable(); }
                    long version = repository.touchAccount(caller,publicChanged,now);
                    if (publicChanged) { outbox.append(caller,account.role(),version,snapshot(after,account.createdAt()),now,caller); }
                }
                return response(account,after);
            });
        } catch (DataAccessException | TransactionException ex) { throw unavailable(); }
    }

    private ProfileRepository.Account active(UUID id,boolean lock) {
        var account = repository.account(id,lock).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Account not found"));
        if (account.status() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN,"ACCOUNT_NOT_ACTIVE","Account is not active");
        }
        return account;
    }
    private Details details(ProfileRepository.Account account) {
        if (account.role() == AccountRole.ADMIN) { return null; }
        return repository.details(account).orElseThrow(ProfileService::unavailable);
    }
    private static PrivateProfile response(ProfileRepository.Account account,Details profile) {
        return new PrivateProfile(account.id(),account.username(),account.email(),account.role(),account.status(),account.createdAt(),account.verifiedAt(),profile);
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEPENDENCY_UNAVAILABLE","Profile storage is temporarily unavailable");
    }
}
