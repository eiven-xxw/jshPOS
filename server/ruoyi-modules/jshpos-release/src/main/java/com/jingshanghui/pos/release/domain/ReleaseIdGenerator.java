package com.jingshanghui.pos.release.domain;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;

/** 生成服务端发布ULID；发布、批次、任务、事件和审计标识不得由客户端提供。 */
@Component
public final class ReleaseIdGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public ReleaseIdGenerator(Clock clock) { this(clock, new SecureRandom()); }
    ReleaseIdGenerator(Clock clock, SecureRandom random) { this.clock = clock; this.random = random; }

    public synchronized String next() {
        long timestamp = clock.millis();
        if (timestamp < 0 || timestamp > 0xFFFFFFFFFFFFL) throw new IllegalStateException("UPG-ID-001: 时间超出ULID范围");
        byte[] bytes = new byte[16];
        for (int index = 5; index >= 0; index--) { bytes[index] = (byte) timestamp; timestamp >>>= 8; }
        byte[] randomness = new byte[10];
        random.nextBytes(randomness);
        System.arraycopy(randomness, 0, bytes, 6, randomness.length);
        BigInteger value = new BigInteger(1, bytes);
        char[] encoded = new char[26];
        for (int index = 25; index >= 0; index--) {
            BigInteger[] divided = value.divideAndRemainder(BigInteger.valueOf(32));
            encoded[index] = ALPHABET[divided[1].intValue()];
            value = divided[0];
        }
        return new String(encoded);
    }
}
