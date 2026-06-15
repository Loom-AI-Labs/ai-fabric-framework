package ai.fabric.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

/**
 * Minimal ULID generator (Crockford Base32, 26 chars).
 *
 * <p>ULID is used for idempotency keys and request correlation where ordering by time is useful.</p>
 *
 * <p>This implementation is thread-safe.</p>
 */
public final class UlidGenerator {

    private static final char[] CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final SecureRandom random;
    private final Clock clock;

    public UlidGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    public UlidGenerator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Generate a new ULID string.
     *
     * @return 26-character ULID
     */
    public String nextUlid() {
        long time = clock.millis();
        byte[] entropy = new byte[10]; // 80 bits
        random.nextBytes(entropy);
        return encode(time, entropy);
    }

    private String encode(long timeMillis, byte[] entropy) {
        char[] out = new char[26];

        // 48-bit timestamp → 10 chars (base32)
        long time = timeMillis & 0xFFFFFFFFFFFFL;
        for (int i = 9; i >= 0; i--) {
            int index = (int) (time & 31L);
            out[i] = CROCKFORD_BASE32[index];
            time >>>= 5;
        }

        // 80-bit entropy → 16 chars (base32)
        int idx = 10;
        int buffer = 0;
        int bufferBits = 0;
        for (byte b : entropy) {
            buffer = (buffer << 8) | (b & 0xFF);
            bufferBits += 8;
            while (bufferBits >= 5) {
                int shift = bufferBits - 5;
                int val = (buffer >>> shift) & 31;
                out[idx++] = CROCKFORD_BASE32[val];
                bufferBits -= 5;
            }
        }

        // Any remaining bits (should be 0..4) pad with zeros
        if (bufferBits > 0 && idx < 26) {
            int val = (buffer << (5 - bufferBits)) & 31;
            out[idx++] = CROCKFORD_BASE32[val];
        }

        // ULID must be exactly 26 chars
        while (idx < 26) {
            out[idx++] = CROCKFORD_BASE32[0];
        }

        return new String(out);
    }
}

