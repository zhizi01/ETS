package com.github.zhizi01.ets.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 加盐 SHA-256 口令散列，格式为 {salt}${hash}。
 * 模拟器内部使用，不引入额外的加密依赖。
 */
public final class PasswordUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    public static String hash(String raw) {
        byte[] salt = new byte[12];
        RANDOM.nextBytes(salt);
        String saltStr = Base64.getUrlEncoder().withoutPadding().encodeToString(salt);
        return saltStr + "$" + digest(raw, saltStr);
    }

    public static boolean matches(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        int idx = stored.indexOf('$');
        if (idx <= 0) {
            return false;
        }
        String salt = stored.substring(0, idx);
        String expect = stored.substring(idx + 1);
        return MessageDigest.isEqual(
                digest(raw, salt).getBytes(StandardCharsets.UTF_8),
                expect.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String raw, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            md.update(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("口令散列失败", e);
        }
    }
}
