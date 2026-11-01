package vn.agriculture.soa.user.admin;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.agriculture.soa.user.account.AccountRole;
import vn.agriculture.soa.user.account.AccountStatus;
import vn.agriculture.soa.user.admin.AdminDtos.AccountDetail;
import vn.agriculture.soa.user.admin.AdminDtos.AccountSummary;
import vn.agriculture.soa.user.admin.AdminDtos.PagedAccounts;
import vn.agriculture.soa.user.admin.AdminDtos.UpdateStatusRequest;
import vn.agriculture.soa.user.auth.AuthDtos.Profile;
import vn.agriculture.soa.user.auth.AuthDtos.Register;
import vn.agriculture.soa.user.auth.AuthDtos.Registered;
import vn.agriculture.soa.user.error.ApiErrorWriter;
import vn.agriculture.soa.user.error.FallbackErrorController;
import vn.agriculture.soa.user.profile.ProfileDtos.Farmer;
import vn.agriculture.soa.user.security.SecurityConfiguration;

@WebMvcTest(controllers = {AdminAccountController.class, FallbackErrorController.class})
@Import({SecurityConfiguration.class, ApiErrorWriter.class})
class AdminAccountWebTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AdminAccountService service;

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ADMIN_ID = "22222222-2222-2222-2222-222222222222";

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject(ADMIN_ID).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void createAccount_serializes201() throws Exception {
        Register req = new Register("new_farmer", "farmer@example.com", "Password@123", AccountRole.FARMER,
                new Profile("Farmer A", null, "0900000000", "Addr", "Dong Thap", "Cao Lanh", null, null));
        when(service.createAccount(any(), any())).thenReturn(
                new Registered(TARGET_ID, "new_farmer", "farmer@example.com", AccountRole.FARMER, "UNVERIFIED", Instant.now(), "SENT")
        );

        mvc.perform(post("/api/v1/admin/accounts")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("accountId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("status").value("UNVERIFIED"))
                .andExpect(jsonPath("verificationEmailStatus").value("SENT"));
    }

    @Test
    void listAccounts_paged() throws Exception {
        AccountSummary item = new AccountSummary(TARGET_ID, "farmer_one", "farmer@example.com", AccountRole.FARMER,
                AccountStatus.ACTIVE, Instant.now());
        when(service.listAccounts(0, 20, null, null)).thenReturn(
                new PagedAccounts(List.of(item), 0, 20, 1, 1)
        );

        mvc.perform(get("/api/v1/admin/accounts").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("totalElements").value(1))
                .andExpect(jsonPath("items[0].accountId").value(TARGET_ID.toString()));
    }

    @Test
    void getAccount_detail() throws Exception {
        Farmer farmer = new Farmer("Farmer Name", "0900000000", "Addr", "Dong Thap", "Cao Lanh", null);
        AccountDetail detail = new AccountDetail(TARGET_ID, "farmer_one", "farmer@example.com", AccountRole.FARMER,
                AccountStatus.ACTIVE, Instant.now(), Instant.now(), farmer);
        when(service.getAccount(TARGET_ID)).thenReturn(detail);

        mvc.perform(get("/api/v1/admin/accounts/" + TARGET_ID).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("accountId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("profile.fullName").value("Farmer Name"));
    }

    @Test
    void updateStatus_callsService() throws Exception {
        UpdateStatusRequest req = new UpdateStatusRequest(AccountStatus.LOCKED, "Fraud check");
        AccountDetail detail = new AccountDetail(TARGET_ID, "farmer_one", "farmer@example.com", AccountRole.FARMER,
                AccountStatus.LOCKED, Instant.now(), Instant.now(), null);
        when(service.updateStatus(any(), eq(TARGET_ID), eq(AccountStatus.LOCKED), eq("Fraud check"))).thenReturn(detail);

        mvc.perform(patch("/api/v1/admin/accounts/" + TARGET_ID + "/status")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("status").value("LOCKED"))
                .andExpect(jsonPath("adminLock").doesNotExist())
                .andExpect(jsonPath("profile").doesNotExist());
    }

    @Test
    void deleteAccount_returns204() throws Exception {
        doNothing().when(service).deleteAccount(any(), eq(TARGET_ID));

        mvc.perform(delete("/api/v1/admin/accounts/" + TARGET_ID).with(adminJwt()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }
}
