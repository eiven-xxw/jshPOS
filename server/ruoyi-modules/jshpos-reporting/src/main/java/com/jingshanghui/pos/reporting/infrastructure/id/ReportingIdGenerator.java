package com.jingshanghui.pos.reporting.infrastructure.id;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;

/** 生成不含租户、门店和时间明文语义的标准大写 ULID。 */
@Component
public class ReportingIdGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ReportingIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public synchronized String next() {
        long timestamp = clock.millis();
        if (timestamp < 0 || timestamp > 0xFFFFFFFFFFFFL) {
            throw new IllegalStateException("RPT-ID-001: 时间戳越界");
        }
        byte[] bytes = new byte[16];
        for (int index = 5; index >= 0; index--) {
            bytes[index] = (byte) timestamp;
            timestamp >>>= 8;
        }
        byte[] randomness = new byte[10];
        random.nextBytes(randomness);
        System.arraycopy(randomness, 0, bytes, 6, randomness.length);
        BigInteger value = new BigInteger(1, bytes);
        char[] result = new char[26];
        for (int index = 25; index >= 0; index--) {
            BigInteger[] divided = value.divideAndRemainder(BigInteger.valueOf(32));
            result[index] = ALPHABET[divided[1].intValue()];
            value = divided[0];
        }
        return new String(result);
    }
}
