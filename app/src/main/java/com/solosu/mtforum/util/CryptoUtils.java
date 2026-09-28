package com.solosu.mtforum.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 凭据加密工具（build60 新增）。
 *
 * <p>多账号自动签到需要在本地保存论坛密码，用来在 Cookie 掉线时自动重登。
 * 明文存 SharedPreferences 等于裸奔，这里统一走 Android KeyStore 的
 * AES/GCM-256 硬件密钥加密，密钥不出 TEE，App 卸载即失效。
 *
 * <p>密文格式：{@code ks:Base64(IV(12B) + CipherText + GCMTag)}。
 * 个别定制 ROM 的 KeyStore 不可用时降级为 {@code ob:} 混淆存储（仅防肉眼读取，
 * 不具备密码学强度），保证功能不因厂商问题整个失效。
 */
public final class CryptoUtils {

    private static final String KEY_ALIAS = "mtforum_account_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;

    private static final String PREFIX_KEYSTORE = "ks:";
    private static final String PREFIX_OBFUSCATED = "ob:";

    /** 降级混淆用的固定盐，仅用于 KeyStore 不可用的兜底路径 */
    private static final byte[] FALLBACK_SALT =
            "MTForum#2026$solosu".getBytes(StandardCharsets.UTF_8);

    private static volatile SecretKey cachedKey;

    private CryptoUtils() {
    }

    // ==================== 对外接口 ====================

    /** 加密。入参为空返回空串；任何异常都会降级而不是抛出，调用方无需 try-catch。 */
    public static String encrypt(String plain) {
        if (TextUtils.isEmpty(plain)) return "";
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX_KEYSTORE + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Throwable t) {
            return PREFIX_OBFUSCATED + obfuscate(plain);
        }
    }

    /** 解密。失败（换机/清 KeyStore/密文损坏）返回空串，调用方按“无密码”处理。 */
    public static String decrypt(String stored) {
        if (TextUtils.isEmpty(stored)) return "";
        try {
            if (stored.startsWith(PREFIX_OBFUSCATED)) {
                return deobfuscate(stored.substring(PREFIX_OBFUSCATED.length()));
            }
            String body = stored.startsWith(PREFIX_KEYSTORE)
                    ? stored.substring(PREFIX_KEYSTORE.length()) : stored;
            byte[] combined = Base64.decode(body, Base64.NO_WRAP);
            if (combined.length <= IV_LENGTH_BYTE) return "";
            byte[] iv = new byte[IV_LENGTH_BYTE];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTE);
            byte[] cipherText = new byte[combined.length - IV_LENGTH_BYTE];
            System.arraycopy(combined, IV_LENGTH_BYTE, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 是否为本工具产出的密文（用于旧数据迁移判断） */
    public static boolean isEncrypted(String value) {
        return value != null
                && (value.startsWith(PREFIX_KEYSTORE) || value.startsWith(PREFIX_OBFUSCATED));
    }

    // ==================== 内部实现 ====================

    private static SecretKey getOrCreateKey() throws Exception {
        SecretKey local = cachedKey;
        if (local != null) return local;
        synchronized (CryptoUtils.class) {
            if (cachedKey != null) return cachedKey;
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
                if (entry instanceof KeyStore.SecretKeyEntry) {
                    cachedKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                    return cachedKey;
                }
                keyStore.deleteEntry(KEY_ALIAS);
            }
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build();
            KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(spec);
            cachedKey = generator.generateKey();
            return cachedKey;
        }
    }

    private static String obfuscate(String plain) {
        byte[] raw = plain.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (byte) (raw[i] ^ FALLBACK_SALT[i % FALLBACK_SALT.length]);
        }
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String deobfuscate(String encoded) {
        byte[] raw = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (byte) (raw[i] ^ FALLBACK_SALT[i % FALLBACK_SALT.length]);
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
