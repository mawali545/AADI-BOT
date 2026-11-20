package com.dr.candletracker;

import android.content.Context;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;

/**
 * Creates a fresh, in-memory RSA identity for each app session.
 *
 * The bridge key is transport-only: it protects messages between this app's
 * trusted Market-QX WebView and its native listener. Persisting it is unnecessary.
 * Using a session key avoids device-vendor AndroidKeyStore AES-GCM provisioning,
 * stale wrapped-key records, and key invalidation failures seen on some Android 10
 * devices. The private key never leaves this process and is discarded on exit.
 */
public final class AndroidKeyStoreRsaKeyStore {
    private AndroidKeyStoreRsaKeyStore() {}

    public static KeyPair loadOrCreate(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("Application context is required");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        if (pair == null || pair.getPrivate() == null || pair.getPublic() == null) {
            throw new IllegalStateException("Session RSA identity generation returned an incomplete key pair");
        }
        return pair;
    }
}
