package vn.agriculture.soa.user.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.agriculture.soa.user.error.ApiErrorWriter;

public class ServiceKeyFilter extends OncePerRequestFilter {
    private final Map<String, byte[]> keys = new HashMap<>();
    private final ApiErrorWriter errors;

    public ServiceKeyFilter(InternalKeysProperties properties, ApiErrorWriter errors) {
        this.errors = errors;
        if (properties.keys() != null) {
            properties.keys().forEach((caller, key) -> {
                if (key != null && !key.isBlank()) {
                    if (key.getBytes(StandardCharsets.UTF_8).length < 32) {
                        throw new IllegalArgumentException("Internal caller keys must be at least 32 bytes");
                    }
                    byte[] value = key.getBytes(StandardCharsets.UTF_8);
                    if (keys.values().stream().anyMatch(existing -> MessageDigest.isEqual(existing, value))) {
                        throw new IllegalArgumentException("Internal callers must use distinct keys");
                    }
                    keys.put(caller, value);
                }
            });
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Fixed endpoint allowlist: this credential never authorizes other APIs.
        if (!"GET".equals(request.getMethod()) || !request.getServletPath()
                .matches("/internal/v1/accounts/[^/]+/access-state")) {
            errors.write(request, response, 403, "FORBIDDEN", "Internal endpoint is not allowed");
            return;
        }
        var headers = request.getHeaders("X-Service-Key");
        String supplied = headers.hasMoreElements() ? headers.nextElement() : null;
        if (supplied == null || headers.hasMoreElements() || supplied.length() > 1024) {
            errors.write(request, response, 401, "UNAUTHORIZED", "Invalid service credential");
            return;
        }
        byte[] candidate = supplied.getBytes(StandardCharsets.UTF_8);
        String caller = null;
        for (var entry : keys.entrySet()) {
            if (MessageDigest.isEqual(candidate, entry.getValue())) { caller = entry.getKey(); }
        }
        if (caller == null) {
            errors.write(request, response, 401, "UNAUTHORIZED", "Invalid service credential");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(caller, null,
                List.of(new SimpleGrantedAuthority("INTERNAL_ACCESS_STATE"))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
