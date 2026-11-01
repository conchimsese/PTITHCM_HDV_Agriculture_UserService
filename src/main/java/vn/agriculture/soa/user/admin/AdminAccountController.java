package vn.agriculture.soa.user.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.auth.AuthDtos.Register;
import vn.agriculture.soa.user.auth.AuthDtos.Registered;
import static vn.agriculture.soa.user.admin.AdminDtos.*;

@RestController
@ConditionalOnProperty(name="user.auth.enabled", havingValue="true", matchIfMissing=true)
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {
    private final AdminAccountService accounts;

    public AdminAccountController(AdminAccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    public ResponseEntity<Registered> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Register request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "private, no-store")
                .body(accounts.createAccount(UUID.fromString(jwt.getSubject()), request));
    }

    @GetMapping
    public ResponseEntity<PagedAccounts> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) AccountRole role,
            @RequestParam(required = false) AccountStatus status
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(accounts.listAccounts(page, size, role, status));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountDetail> get(@PathVariable UUID accountId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(accounts.getAccount(accountId));
    }

    @PatchMapping("/{accountId}")
    public ResponseEntity<AccountDetail> patchProfile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId,
            @RequestBody JsonNode body
    ) {
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(accounts.patchProfile(UUID.fromString(jwt.getSubject()), accountId, body));
    }

    @PatchMapping("/{accountId}/status")
    public ResponseEntity<AccountSummary> updateStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        AccountDetail account = accounts.updateStatus(UUID.fromString(jwt.getSubject()), accountId, request.status(), request.reason());
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(new AccountSummary(account.accountId(), account.username(), account.email(), account.role(), account.status(), account.createdAt()));
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId
    ) {
        accounts.deleteAccount(UUID.fromString(jwt.getSubject()), accountId);
        return ResponseEntity.noContent()
                .header("Cache-Control", "private, no-store")
                .build();
    }
}
