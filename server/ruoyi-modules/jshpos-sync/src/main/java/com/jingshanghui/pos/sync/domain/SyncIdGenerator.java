package com.jingshanghui.pos.sync.domain;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;

@Component
public class SyncIdGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final Clock clock;
    private final SecureRandom random;

    public SyncIdGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    SyncIdGenerator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public synchronized String next() {
        long timestamp = clock.millis();
        if (timestamp < 0 || timestamp > 0xFFFFFFFFFFFFL) {
            throw new IllegalStateException("SYNC_ID_INVALID: timestamp outside ULID range");
        }
        byte[] bytes = new byte[16];
        bytes[0] = (byte) (timestamp >>> 40);
        bytes[1] = (byte) (timestamp >>> 32);
        bytes[2] = (byte) (timestamp >>> 24);
        bytes[3] = (byte) (timestamp >>> 16);
        bytes[4] = (byte) (timestamp >>> 8);
        bytes[5] = (byte) timestamp;
        byte[] randomness = new byte[10];
        random.nextBytes(randomness);
        System.arraycopy(randomness, 0, bytes, 6, randomness.length);
        BigInteger value = new BigInteger(1, bytes);
        BigInteger radix = BigInteger.valueOf(32);
        char[] encoded = new char[26];
        for (int index = encoded.length - 1; index >= 0; index--) {
            BigInteger[] division = value.divideAndRemainder(radix);
            encoded[index] = ALPHABET[division[1].intValue()];
            value = division[0];
        }
        return new String(encoded);
    }
}
