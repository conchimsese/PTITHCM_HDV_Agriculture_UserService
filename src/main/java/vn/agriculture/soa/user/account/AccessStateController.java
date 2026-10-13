package vn.agriculture.soa.user.account;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccessStateController {
    private final AccessStateService service;

    public AccessStateController(AccessStateService service) { this.service = service; }

    @GetMapping("/internal/v1/accounts/{accountId}/access-state")
    public ResponseEntity<AccessState> get(@PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(accountId));
    }
}
