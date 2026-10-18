import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Java 21 source launcher: java scripts/GenerateLocalAuth.java. Never prints secret material. */
class GenerateLocalAuth {
    public static void main(String[] args) throws Exception {
        Path env=Path.of(".env");
        if (!Files.exists(env)) { Files.copy(Path.of(".env.example"),env); }
        var properties=new Properties();
        try (var reader=Files.newBufferedReader(env,StandardCharsets.UTF_8)) { properties.load(reader); }
        Path directory=Path.of("secrets/local-auth"); Files.createDirectories(directory);
        Path privateKey=directory.resolve("private.pem"), publicKey=directory.resolve("public.pem");
        if (!Files.exists(privateKey) && !Files.exists(publicKey)) {
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
            var pair=generator.generateKeyPair();
            Files.writeString(privateKey,pem("PRIVATE KEY",pair.getPrivate().getEncoded()),StandardOpenOption.CREATE_NEW);
            Files.writeString(publicKey,pem("PUBLIC KEY",pair.getPublic().getEncoded()),StandardOpenOption.CREATE_NEW);
        } else if (!Files.exists(privateKey) || !Files.exists(publicKey)) {
            throw new IllegalStateException("Incomplete existing key pair; restore missing file, do not silently rotate");
        }
        var updates=new LinkedHashMap<String,String>();
        updates.put("JWT_PRIVATE_KEY_PATH",privateKey.toString().replace('\\','/'));
        updates.put("JWT_PUBLIC_KEY_PATH",publicKey.toString().replace('\\','/'));
        updates.put("JWT_KEY_ID","local-"+java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
        updates.put("OTP_HMAC_SECRET",HexFormat.of().formatHex(secret));
        String text=Files.readString(env,StandardCharsets.UTF_8);
        for (var entry:updates.entrySet()) {
            if (!properties.getProperty(entry.getKey(),"").isBlank()) { continue; }
            String pattern="(?m)^"+entry.getKey()+"=[^\\r\\n]*";
            if (java.util.regex.Pattern.compile(pattern).matcher(text).find()) {
                text=text.replaceAll(pattern,java.util.regex.Matcher.quoteReplacement(entry.getKey()+"="+entry.getValue()));
            } else { text += System.lineSeparator()+entry.getKey()+"="+entry.getValue()+System.lineSeparator(); }
        }
        Files.writeString(env,text,StandardCharsets.UTF_8);
        System.out.println("Local auth keys and OTP secret prepared. Existing nonempty .env values preserved. No secrets printed.");
    }
    private static String pem(String type,byte[] data) {
        return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(data)+"\n-----END "+type+"-----\n";
    }
}
