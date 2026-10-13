package vn.agriculture.soa.user.account;

import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.agriculture.soa.user.error.ApiException;

@Service
public class AccessStateService {
    private final AccountStateRepository repository;

    public AccessStateService(AccountStateRepository repository) { this.repository = repository; }

    public AccessState get(UUID accountId) {
        try {
            return repository.find(accountId).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));
        } catch (DataAccessException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ACCESS_STATE_UNAVAILABLE",
                    "Account state is temporarily unavailable");
        }
    }
}
