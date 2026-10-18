package vn.agriculture.soa.user.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import vn.agriculture.soa.user.account.AccountRole;

public class JwtTokens {
    public static final String ISSUER = "urn:soa:user-service";
    private final String keyId;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;

    public JwtTokens(AuthProperties settings, Clock clock) {
        this.clock = clock; this.keyId = settings.keyId();
        try {
            if (keyId == null || keyId.isBlank()) { throw new IllegalArgumentException(); }
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(pem(settings.privateKeyPath())));
            var keys = new ArrayList<com.nimbusds.jose.jwk.JWK>();
            var publicFiles = new LinkedHashMap<String,String>();
            publicFiles.put(keyId, settings.publicKeyPath());
            if (settings.previousKeyId()!=null && !settings.previousKeyId().isBlank()) {
                if (keyId.equals(settings.previousKeyId()) || settings.previousPublicKeyPath()==null || settings.previousPublicKeyPath().isBlank()) { throw new IllegalArgumentException(); }
                publicFiles.put(settings.previousKeyId(), settings.previousPublicKeyPath());
            } else if (settings.previousPublicKeyPath()!=null && !settings.previousPublicKeyPath().isBlank()) { throw new IllegalArgumentException(); }
            for (var entry : publicFiles.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) { continue; }
                var pub = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(pem(entry.getValue())));
                if (pub.getModulus().bitLength() < 2048) { throw new IllegalArgumentException(); }
                keys.add(new RSAKey.Builder(pub).keyID(entry.getKey()).algorithm(JWSAlgorithm.RS256).build());
            }
            var current = keys.stream().filter(k -> k.getKeyID().equals(keyId)).map(k -> (RSAKey) k).findFirst().orElseThrow();
            if (!current.toRSAPublicKey().getModulus().equals(privateKey.getModulus())) { throw new IllegalArgumentException(); }
            // Verify the key pair, including exponent, without disclosing either key.
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey); signature.update(new byte[]{1,2,3}); byte[] proof = signature.sign();
            signature.initVerify(current.toRSAPublicKey()); signature.update(new byte[]{1,2,3});
            if (!signature.verify(proof)) { throw new IllegalArgumentException(); }
            var signing = new RSAKey.Builder(current.toRSAPublicKey()).privateKey(privateKey).keyID(keyId).algorithm(JWSAlgorithm.RS256).build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signing)));
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                    (selector, context) -> selector.select(new JWKSet(keys))));
            var nimbus = new NimbusJwtDecoder(processor);
            var timestamp = new JwtTimestampValidator(Duration.ofSeconds(60)); timestamp.setClock(clock);
            nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, new JwtIssuerValidator(ISSUER), this::validate));
            decoder = nimbus;
        } catch (Exception ex) {
            throw new IllegalStateException("Configure matching RSA private/public keys and JWT_KEY_ID (RSA >=2048, PKCS8/X509 PEM); no key material is logged");
        }
    }
    private OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            if (!"RS256".equals(jwt.getHeaders().get("alg")) || !(jwt.getHeaders().get("kid") instanceof String kid)
                    || kid.isBlank() || !jwt.getAudience().contains("soa-api")) { throw new IllegalArgumentException(); }
            if (!UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())) { throw new IllegalArgumentException(); }
            UUID.fromString(jwt.getId()); AccountRole.valueOf(jwt.getClaimAsString("role"));
            if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                    || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(60))
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt()).getSeconds() > 900) { throw new IllegalArgumentException(); }
            return OAuth2TokenValidatorResult.success();
        } catch (Exception ex) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid JWT claims", null));
        }
    }
    private static byte[] pem(String file) throws Exception {
        String text = Files.readString(Path.of(file)).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(text);
    }
    public String issue(UUID id, AccountRole role) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of("soa-api"))
                .subject(id.toString()).issuedAt(now).expiresAt(now.plusSeconds(900)).id(UUID.randomUUID().toString())
                .claim("role",role.name()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(),claims)).getTokenValue();
    }
    public JwtDecoder decoder() { return decoder; }
}
