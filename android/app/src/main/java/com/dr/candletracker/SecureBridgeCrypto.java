package com.dr.candletracker;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Session-scoped RSA-OAEP-256 + AES-256-GCM transport for the WebView bridge. */
public final class SecureBridgeCrypto {
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final Gson GSON = new Gson();
    private final KeyPair keys;

    public SecureBridgeCrypto() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    public SecureBridgeCrypto(KeyPair protectedKeyPair) {
        if (protectedKeyPair == null || protectedKeyPair.getPrivate() == null || protectedKeyPair.getPublic() == null)
            throw new IllegalArgumentException("Protected RSA key pair is required");
        keys = protectedKeyPair;
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }

    public String decryptEnvelope(String json) throws Exception {
        JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
        byte[] wrapped = Base64.getDecoder().decode(envelope.get("wrappedKey").getAsString());
        byte[] iv = Base64.getDecoder().decode(envelope.get("iv").getAsString());
        byte[] ciphertext = Base64.getDecoder().decode(envelope.get("ciphertext").getAsString());
        if (iv.length != 12 || wrapped.length == 0 || ciphertext.length < 16)
            throw new IllegalArgumentException("Invalid AES-GCM envelope");
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, keys.getPrivate(), OAEP);
        byte[] rawAes = rsa.doFinal(wrapped);
        if (rawAes.length != 32) throw new IllegalArgumentException("Invalid AES-256 key");
        SecretKey aes = new SecretKeySpec(rawAes, "AES");
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        byte[] plain = gcm.doFinal(ciphertext);
        return new String(plain, StandardCharsets.UTF_8);
    }

    static String encryptForTest(SecureBridgeCrypto target, String plaintext) throws Exception {
        byte[] aesBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(aesBytes);
        SecretKey aes = new SecretKeySpec(aesBytes, "AES");
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, target.keys.getPublic(), OAEP);
        byte[] wrapped = rsa.doFinal(aesBytes);
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
        byte[] encrypted = gcm.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        JsonObject out = new JsonObject();
        out.addProperty("wrappedKey", Base64.getEncoder().encodeToString(wrapped));
        out.addProperty("iv", Base64.getEncoder().encodeToString(iv));
        out.addProperty("ciphertext", Base64.getEncoder().encodeToString(encrypted));
        return GSON.toJson(out);
    }
}
