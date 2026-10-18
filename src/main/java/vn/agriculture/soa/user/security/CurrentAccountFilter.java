package vn.agriculture.soa.user.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.agriculture.soa.user.account.AccessStateService;
import vn.agriculture.soa.user.error.ApiErrorWriter;
import vn.agriculture.soa.user.error.ApiException;

public class CurrentAccountFilter extends OncePerRequestFilter {
    private final AccessStateService state;
    private final ApiErrorWriter errors;
    public CurrentAccountFilter(AccessStateService state,ApiErrorWriter errors) { this.state=state; this.errors=errors; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        boolean publicRequest=request.getServletPath().startsWith("/actuator/health")
                || ("POST".equals(request.getMethod()) && SecurityConfiguration.PUBLIC_AUTH.contains(request.getServletPath()));
        if (!publicRequest && auth instanceof JwtAuthenticationToken token && auth.isAuthenticated()) {
            try {
                var current=state.get(UUID.fromString(token.getToken().getSubject()));
                if (!current.accessAllowed()) {
                    errors.write(request,response,403,"ACCOUNT_NOT_ACTIVE","Account is not active"); return;
                }
                if (!current.role().name().equals(token.getToken().getClaimAsString("role"))) {
                    errors.write(request,response,403,"FORBIDDEN","Token role does not match account"); return;
                }
            } catch (ApiException ex) {
                int status=ex.status().value()==404?401:ex.status().value();
                errors.write(request,response,status,status==401?"UNAUTHORIZED":ex.code(),status==401?"Invalid account":ex.getMessage());
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
