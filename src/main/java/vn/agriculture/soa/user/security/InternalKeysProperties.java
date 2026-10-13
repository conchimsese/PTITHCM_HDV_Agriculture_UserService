package vn.agriculture.soa.user.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "user.internal")
public record InternalKeysProperties(Map<String, String> keys) { }
