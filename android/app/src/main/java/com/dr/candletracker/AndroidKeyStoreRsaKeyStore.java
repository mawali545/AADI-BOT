package com.dr.candletracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Persists the RSA private key encrypted by a non-exportable Android Keystore AES-GCM key.
 * This keeps RSA-OAEP SHA-256/MGF1 SHA-256 compatible with WebCrypto on Android 10,
 * while the wrapping AES key itself never leaves Android Keystore.
 */
public final class AndroidKeyStoreRsaKeyStore {
    private static final String STORE = "dr_secure_key_material";
    private static final String AES_ALIAS = "dr_candle_tracker_wrapping_aes_v1";
    private static final String PREF_PUBLIC = "rsa_public_x509";
    private static final String PREF_PRIVATE_IV = "rsa_private_iv";
    private static final String PREF_PRIVATE_CIPHER = "rsa_private_ciphertext";

    private AndroidKeyStoreRsaKeyStore() {}

    public static KeyPair loadOrCreate(Context context) throws Exception {
        Context app = context.getApplicationContext();
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey wrappingKey;
        if (ks.containsAlias(AES_ALIAS)) {
            wrappingKey = (SecretKey) ks.getKey(AES_ALIAS, null);
        } else {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(AES_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            wrappingKey = generator.generateKey();
        }
        if (wrappingKey == null) throw new IllegalStateException("Android Keystore wrapping key unavailable");

        SharedPreferences prefs = app.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String pub = prefs.getString(PREF_PUBLIC, null);
        String ivText = prefs.getString(PREF_PRIVATE_IV, null);
        String cipherText = prefs.getString(PREF_PRIVATE_CIPHER, null);
        if (pub != null && ivText != null && cipherText != null) {
            try {
                byte[] iv = Base64.getDecoder().decode(ivText);
                byte[] encrypted = Base64.getDecoder().decode(cipherText);
                Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                aes.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(128, iv));
                byte[] privateBytes = aes.doFinal(encrypted);
                KeyFactory factory = KeyFactory.getInstance("RSA");
                PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pub)));
                PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                return new KeyPair(publicKey, privateKey);
            } catch (Exception corrupted) {
                // Corrupt or invalidated material is replaced with a fresh session identity.
                prefs.edit().clear().commit();
            }
        }

        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048, new SecureRandom());
        KeyPair pair = rsa.generateKeyPair();
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, wrappingKey, new GCMParameterSpec(128, iv));
        byte[] encryptedPrivate = aes.doFinal(pair.getPrivate().getEncoded());
        boolean saved = prefs.edit()
                .putString(PREF_PUBLIC, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                .putString(PREF_PRIVATE_IV, Base64.getEncoder().encodeToString(iv))
                .putString(PREF_PRIVATE_CIPHER, Base64.getEncoder().encodeToString(encryptedPrivate))
                .commit();
        if (!saved) throw new IllegalStateException("Unable to persist protected RSA identity");
        return pair;
    }
}
