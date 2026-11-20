package com.dr.candletracker;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SecureBridgeCryptoTest {
    @Test public void rsaOaepAndAesGcmRoundTrip() throws Exception {
        SecureBridgeCrypto crypto = new SecureBridgeCrypto();
        JSONObject source = new JSONObject();
        source.put("asset", "EURUSD_otc");
        source.put("price", 1.08234);
        source.put("timestamp", 1795200000000L);
        String envelope = SecureBridgeCrypto.encryptForTest(crypto, source.toString());
        JSONObject decoded = crypto.decryptEnvelope(envelope);
        assertEquals(source.getString("asset"), decoded.getString("asset"));
        assertEquals(source.getDouble("price"), decoded.getDouble("price"), 0.0000001);
        assertEquals(source.getLong("timestamp"), decoded.getLong("timestamp"));
    }

    @Test public void tamperedCiphertextIsRejected() throws Exception {
        SecureBridgeCrypto crypto = new SecureBridgeCrypto();
        String envelope = SecureBridgeCrypto.encryptForTest(crypto, "{\"price\":1}");
        JSONObject changed = new JSONObject(envelope);
        String cipher = changed.getString("ciphertext");
        char first = cipher.charAt(0);
        changed.put("ciphertext", (first == 'A' ? "B" : "A") + cipher.substring(1));
        try {
            crypto.decryptEnvelope(changed.toString());
            fail("Tampered AES-GCM ciphertext must fail authentication");
        } catch (javax.crypto.AEADBadTagException expected) {
            // Expected authentication failure.
        } catch (Exception expected) {
            // Provider may wrap the authentication failure.
            assertNotNull(expected);
        }
    }
}
