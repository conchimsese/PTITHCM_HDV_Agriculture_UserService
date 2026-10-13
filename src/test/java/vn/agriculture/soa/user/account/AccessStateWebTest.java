package vn.agriculture.soa.user.account;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.agriculture.soa.user.error.ApiErrorWriter;
import vn.agriculture.soa.user.error.FallbackErrorController;
import vn.agriculture.soa.user.security.SecurityConfiguration;

@WebMvcTest(controllers = {AccessStateController.class, FallbackErrorController.class}, properties = {
        "user.internal.keys.gateway=test-only-gateway-key-32-bytes-long",
        "user.internal.keys.trading=test-only-trading-key-32-bytes-long"})
@Import({SecurityConfiguration.class, ApiErrorWriter.class, AccessStateService.class})
class AccessStateWebTest {
    private static final String KEY = "test-only-gateway-key-32-bytes-long";
    private static final UUID ID = UUID.fromString("42c4b826-6633-4636-8e2f-13638973c3d9");
    private static final String PATH = "/internal/v1/accounts/" + ID + "/access-state";
    @Autowired MockMvc mvc;
    @MockitoBean AccountStateRepository repository;

    private MockHttpServletRequestBuilder request(String path) {
        return get(path).servletPath(path).header("X-Service-Key", KEY);
    }

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void returnsCurrentStateIncludingNonActiveAccounts(AccountStatus state) throws Exception {
        when(repository.find(ID)).thenReturn(Optional.of(new AccessState(ID, state, AccountRole.FARMER,
                state == AccountStatus.ACTIVE)));
        mvc.perform(request(PATH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("accountId").value(ID.toString()))
                .andExpect(jsonPath("role").value("FARMER"))
                .andExpect(jsonPath("status").value(state.name()))
                .andExpect(jsonPath("accessAllowed").value(state == AccountStatus.ACTIVE))
                .andExpect(jsonPath("email").doesNotExist());
    }

    @Test
    void missingAndInvalidServiceKeysAndSpoofedIdentityCannotReadDb() throws Exception {
        mvc.perform(get(PATH).servletPath(PATH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("code").value("UNAUTHORIZED"));
        mvc.perform(get(PATH).servletPath(PATH).header("X-Service-Key", "wrong")
                        .header("x-user-id", ID).header("x-user-role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).servletPath(PATH).header("Authorization", "Bearer fabricated.jwt.value"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test
    void duplicateKeyHeadersAreRejected() throws Exception {
        mvc.perform(get(PATH).servletPath(PATH).header("X-Service-Key", KEY, KEY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test
    void eachConfiguredCallerKeyIsAccepted() throws Exception {
        when(repository.find(ID)).thenReturn(Optional.of(new AccessState(ID, AccountStatus.ACTIVE, AccountRole.ADMIN, true)));
        mvc.perform(get(PATH).servletPath(PATH).header("X-Service-Key", "test-only-trading-key-32-bytes-long"))
                .andExpect(status().isOk()).andExpect(jsonPath("role").value("ADMIN"));
    }

    @Test
    void invalidUuidReturnsSanitizedContractError() throws Exception {
        String path = "/internal/v1/accounts/not-a-uuid/access-state";
        mvc.perform(request(path)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("timestamp").exists())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("path").value(path))
                .andExpect(jsonPath("message").value("Invalid request"))
                .andExpect(jsonPath("trace").doesNotExist());
        verifyNoInteractions(repository);
    }

    @Test
    void absentAccountReturns404() throws Exception {
        when(repository.find(ID)).thenReturn(Optional.empty());
        mvc.perform(request(PATH)).andExpect(status().isNotFound())
                .andExpect(jsonPath("code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void dbFailureFailsClosedWithoutLeakingSqlOrCredential() throws Exception {
        when(repository.find(ID)).thenThrow(new DataAccessResourceFailureException("secret SQL password"));
        mvc.perform(request(PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value("ACCESS_STATE_UNAVAILABLE"))
                .andExpect(jsonPath("message").value("Account state is temporarily unavailable"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void serviceKeyCannotAuthorizeOtherPathsOrMethods() throws Exception {
        String other = "/internal/v1/accounts/" + ID;
        mvc.perform(request(other)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).servletPath(PATH).header("X-Service-Key", KEY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/me").header("X-Service-Key", KEY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test
    void servletErrorDispatchUsesContractAndDoesNotLeakException() throws Exception {
        mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/actuator/health")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("private secret")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("path").value("/actuator/health"))
                .andExpect(jsonPath("message").value("Request could not be completed"))
                .andExpect(jsonPath("exception").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
