package dev.juniordev.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic, non-security authorization-free percentage sampling. */
final class RolloutPolicy {
    private RolloutPolicy() {}

    static boolean includes(String flagName, String subject, int percentage) {
        if (percentage <= 0) return false;
        if (percentage >= 100) return true;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((flagName + "\u0000" + subject).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int index = 0; index < 4; index++) {
                value = (value << 8) | (digest[index] & 0xffL);
            }
            return (value % 10_000) < percentage * 100L;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
