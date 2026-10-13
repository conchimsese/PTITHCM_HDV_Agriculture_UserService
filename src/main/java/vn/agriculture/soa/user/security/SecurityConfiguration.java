package vn.agriculture.soa.user.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import vn.agriculture.soa.user.error.ApiErrorWriter;

@Configuration
@EnableConfigurationProperties(InternalKeysProperties.class)
public class SecurityConfiguration {
    private final ApiErrorWriter errors;

    public SecurityConfiguration(ApiErrorWriter errors) { this.errors = errors; }

    @Bean
    @Order(1)
    SecurityFilterChain internal(HttpSecurity http, InternalKeysProperties keys) throws Exception {
        defaults(http);
        http.securityMatcher("/internal/**")
                .addFilterBefore(new ServiceKeyFilter(keys, errors), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("INTERNAL_ACCESS_STATE"));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain other(HttpSecurity http) throws Exception {
        defaults(http);
        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    private void defaults(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, cause) -> errors.write(req, res, 401, "UNAUTHORIZED", "Authentication required"))
                        .accessDeniedHandler((req, res, cause) -> errors.write(req, res, 403, "FORBIDDEN", "Access denied")));
    }
}
