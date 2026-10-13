package vn.agriculture.soa.user.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.agriculture.soa.user.error.ApiErrorWriter;

class ServiceKeyConfigurationTest {
    @Test
    void shortKeysFailStartupWithoutPrintingSecrets() {
        assertThatThrownBy(() -> new ServiceKeyFilter(new InternalKeysProperties(Map.of("gateway", "short-secret")),
                mock(ApiErrorWriter.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("short-secret");
    }

    @Test
    void sharedKeyAcrossCallersIsRejected() {
        String key = "test-only-key-with-at-least-32-bytes";
        assertThatThrownBy(() -> new ServiceKeyFilter(new InternalKeysProperties(Map.of("gateway", key, "payment", key)),
                mock(ApiErrorWriter.class))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Internal callers must use distinct keys");
    }

    @Test
    void emptyConfigurationIsValidAndAuthorizesNoCaller() {
        assertThatCode(() -> new ServiceKeyFilter(new InternalKeysProperties(Map.of()), mock(ApiErrorWriter.class)))
                .doesNotThrowAnyException();
    }
}
