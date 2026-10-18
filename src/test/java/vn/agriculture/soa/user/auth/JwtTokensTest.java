package vn.agriculture.soa.user.auth;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtException;
import vn.agriculture.soa.user.account.AccountRole;

class JwtTokensTest {
    static KeyPair current,previous,stranger;
    static AuthProperties settings;
    static Clock clock;
    static JwtTokens tokens;
    @BeforeAll static void setup() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        current=generator.generateKeyPair(); previous=generator.generateKeyPair(); stranger=generator.generateKeyPair();
        Path dir=Files.createTempDirectory("user-jwt-test-");
        Path priv=dir.resolve("private.pem"),pub=dir.resolve("public.pem"),old=dir.resolve("previous.pem");
        Files.writeString(priv,Base64.getEncoder().encodeToString(current.getPrivate().getEncoded()));
        Files.writeString(pub,Base64.getEncoder().encodeToString(current.getPublic().getEncoded()));
        Files.writeString(old,Base64.getEncoder().encodeToString(previous.getPublic().getEncoded()));
        settings=new AuthProperties("test-only-otp-secret-with-32-bytes",priv.toString(),"current",pub.toString(),"old",old.toString(),"user@example.test",false,List.of());
        clock=Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),ZoneOffset.UTC);
        tokens=new JwtTokens(settings,clock);
    }
    private Map<String,Object> claims() {
        var claims=new HashMap<String,Object>();
        claims.put("iss",JwtTokens.ISSUER); claims.put("aud",List.of("soa-api"));
        claims.put("sub",UUID.randomUUID().toString()); claims.put("jti",UUID.randomUUID().toString());
        claims.put("role","FARMER"); claims.put("iat",clock.instant().getEpochSecond()); claims.put("exp",clock.instant().plusSeconds(900).getEpochSecond());
        return claims;
    }
    private String sign(Map<String,Object> data,JWSAlgorithm algorithm,String kid,KeyPair key) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(algorithm).keyID(kid).build(),JWTClaimsSet.parse(data));
        jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate())); return jwt.serialize();
    }
    @Test void correctTokenAndPreviousDeploymentKeyAreAccepted() throws Exception {
        assertThat(tokens.decoder().decode(tokens.issue(UUID.randomUUID(),AccountRole.FARMER)).getClaimAsString("role")).isEqualTo("FARMER");
        assertThatCode(()->tokens.decoder().decode(sign(claims(),JWSAlgorithm.RS256,"old",previous))).doesNotThrowAnyException();
    }
    @Test void wrongSignatureUnknownKidMissingKidAndWrongAlgorithmAreRejected() throws Exception {
        for (String invalid:List.of(sign(claims(),JWSAlgorithm.RS256,"current",stranger),
                sign(claims(),JWSAlgorithm.RS256,"unknown",current),sign(claims(),JWSAlgorithm.RS256,null,current),
                sign(claims(),JWSAlgorithm.RS512,"current",current))) {
            assertThatThrownBy(()->tokens.decoder().decode(invalid)).isInstanceOf(JwtException.class);
        }
        var hmac=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("current").build(),JWTClaimsSet.parse(claims()));
        hmac.sign(new MACSigner(new byte[32]));
        assertThatThrownBy(()->tokens.decoder().decode(hmac.serialize())).isInstanceOf(JwtException.class);
    }
    @Test void wrongIssuerAudienceSubjectRoleAndMissingRequiredClaimsAreRejected() throws Exception {
        var mutations=List.of(Map.entry("iss",(Object)"wrong"),Map.entry("aud",(Object)List.of("wrong")),
                Map.entry("sub",(Object)"not-a-uuid"),Map.entry("role",(Object)"SERVICE"),Map.entry("role",(Object)List.of("FARMER","TRADER")));
        for (var mutation:mutations) {
            var claims=claims(); claims.put(mutation.getKey(),mutation.getValue());
            String invalid=sign(claims,JWSAlgorithm.RS256,"current",current);
            assertThatThrownBy(()->tokens.decoder().decode(invalid)).isInstanceOf(JwtException.class);
        }
        for (String key:List.of("sub","jti","role","iat","exp","iss","aud")) {
            var claims=claims(); claims.remove(key); String invalid=sign(claims,JWSAlgorithm.RS256,"current",current);
            assertThatThrownBy(()->tokens.decoder().decode(invalid)).as("missing required JWT claim %s",key).isInstanceOf(JwtException.class);
        }
    }
    @Test void expiredFutureIssuedAndExcessLifetimeAreRejected() throws Exception {
        var expired=claims(); expired.put("iat",clock.instant().minusSeconds(1200).getEpochSecond()); expired.put("exp",clock.instant().minusSeconds(300).getEpochSecond());
        var future=claims(); future.put("iat",clock.instant().plusSeconds(120).getEpochSecond());
        var longLived=claims(); longLived.put("exp",clock.instant().plusSeconds(901).getEpochSecond());
        for (var data:List.of(expired,future,longLived)) {
            String invalid=sign(data,JWSAlgorithm.RS256,"current",current);
            assertThatThrownBy(()->tokens.decoder().decode(invalid)).isInstanceOf(JwtException.class);
        }
    }
    @Test void sensitiveDtosDoNotExposeSecretsThroughToString() {
        assertThat(new AuthDtos.Login("farmer","secret-password").toString()).doesNotContain("secret-password");
        assertThat(new AuthDtos.Verify("a@example.test","123456").toString()).doesNotContain("123456");
        assertThat(new AuthDtos.TokenPair("secret-access","secret-refresh","Bearer",900,UUID.randomUUID(),AccountRole.FARMER).toString())
                .doesNotContain("secret-access","secret-refresh");
        assertThat(settings.toString()).doesNotContain(settings.otpHmacSecret(),settings.privateKeyPath());
    }
}
