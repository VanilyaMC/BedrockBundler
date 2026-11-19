package dev.bedrockbundler.common.model;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Short deterministic names for geometry and texture files, ported unchanged from GeyserModelEngine
 * ({@code ShortHashUtil}, MIT, copyright zimzaza4 and contributors) so ids match the reference pack.
 */
final class ShortHash {

    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyz_";
    private static final int SUFFIX_LEN = 6;
    private static final long RADIX = 37;
    private static final long SUFFIX_SPACE = (long) Math.pow(RADIX, SUFFIX_LEN);

    private ShortHash() {
    }

    static String modelId(String modelId) {
        return "gm_" + hash(modelId);
    }

    static String textureName(String modelId, String textureName) {
        return "gm_" + hash(modelId + textureName);
    }

    static String hash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            BigInteger num = new BigInteger(1, digest);
            BigInteger totalSpace = BigInteger.valueOf(26).multiply(BigInteger.valueOf(SUFFIX_SPACE));
            BigInteger val = num.mod(totalSpace);
            BigInteger[] divAndRem = val.divideAndRemainder(BigInteger.valueOf(SUFFIX_SPACE));
            int firstIdx = divAndRem[0].intValue();
            long remainder = divAndRem[1].longValue();
            char[] suffix = new char[SUFFIX_LEN];
            for (int i = SUFFIX_LEN - 1; i >= 0; i--) {
                suffix[i] = CHARS.charAt((int) (remainder % RADIX));
                remainder /= RADIX;
            }
            return (char) ('a' + firstIdx) + new String(suffix);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
