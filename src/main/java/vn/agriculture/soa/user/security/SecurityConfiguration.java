package vn.agriculture.soa.user.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import vn.agriculture.soa.user.account.AccessStateService;
import vn.agriculture.soa.user.error.ApiErrorWriter;

@Configuration
@EnableConfigurationProperties(InternalKeysProperties.class)
public class SecurityConfiguration {
    public static final List<String> PUBLIC_AUTH=List.of("/api/v1/auth/register","/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification","/api/v1/auth/login","/api/v1/auth/refresh");
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
    SecurityFilterChain other(HttpSecurity http, ObjectProvider<JwtDecoder> decoders, ObjectProvider<AccessStateService> state) throws Exception {
        defaults(http);
        JwtDecoder decoder=decoders.getIfAvailable();
        if (decoder!=null) {
            var converter=new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_"+jwt.getClaimAsString("role"))));
            http.oauth2ResourceServer(resource -> resource.jwt(config -> config.decoder(decoder).jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint((req,res,cause) -> { res.setHeader("WWW-Authenticate","Bearer"); errors.write(req,res,401,"UNAUTHORIZED","Invalid bearer token"); })
                    .accessDeniedHandler((req,res,cause) -> errors.write(req,res,403,"FORBIDDEN","Access denied")))
                    .addFilterAfter(new CurrentAccountFilter(state.getObject(),errors),BearerTokenAuthenticationFilter.class);
        }
        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers(HttpMethod.POST,PUBLIC_AUTH.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/logout").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/users/me","/api/v1/users/{accountId}",
                        "/api/v1/farmers/{farmerId}","/api/v1/traders/{traderId}").authenticated()
                .requestMatchers(HttpMethod.PUT,"/api/v1/users/me").authenticated()
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
