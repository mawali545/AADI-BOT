package com.dr.candletracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class SecureBridgeCryptoTest {
    @Test public void rsaOaepAndAesGcmRoundTrip() throws Exception {
        SecureBridgeCrypto crypto = new SecureBridgeCrypto();
        String source = "{\"asset\":\"EURUSD_otc\",\"price\":1.08234,\"timestamp\":1795200000000}";
        String envelope = SecureBridgeCrypto.encryptForTest(crypto, source);
        JsonObject decoded = JsonParser.parseString(crypto.decryptEnvelope(envelope)).getAsJsonObject();
        assertEquals("EURUSD_otc", decoded.get("asset").getAsString());
        assertEquals(1.08234, decoded.get("price").getAsDouble(), 0.0000001);
        assertEquals(1795200000000L, decoded.get("timestamp").getAsLong());
    }

    @Test public void tamperedCiphertextIsRejected() throws Exception {
        SecureBridgeCrypto crypto = new SecureBridgeCrypto();
        String envelope = SecureBridgeCrypto.encryptForTest(crypto, "{\"price\":1}");
        JsonObject changed = JsonParser.parseString(envelope).getAsJsonObject();
        String cipher = changed.get("ciphertext").getAsString();
        char first = cipher.charAt(0);
        changed.addProperty("ciphertext", (first == 'A' ? "B" : "A") + cipher.substring(1));
        try {
            crypto.decryptEnvelope(changed.toString());
            fail("Tampered AES-GCM ciphertext must fail authentication");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }
}
