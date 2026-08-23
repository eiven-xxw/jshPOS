package com.jingshanghui.pos.saas.domain;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;

/** 生成 SaaS 事实使用的大写 ULID。 */
@Component
public class SaasIdGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SaasIdGenerator(Clock clock) { this.clock = clock; }

    public synchronized String next() {
        long timestamp = clock.millis();
        if (timestamp < 0 || timestamp > 0xFFFFFFFFFFFFL) throw new IllegalStateException("SAA-ID-001: ULID 时间越界");
        byte[] bytes = new byte[16];
        for (int i = 5; i >= 0; i--) { bytes[i] = (byte) timestamp; timestamp >>>= 8; }
        byte[] entropy = new byte[10]; random.nextBytes(entropy); System.arraycopy(entropy, 0, bytes, 6, 10);
        BigInteger value = new BigInteger(1, bytes); BigInteger radix = BigInteger.valueOf(32);
        char[] result = new char[26];
        for (int i = 25; i >= 0; i--) { BigInteger[] d = value.divideAndRemainder(radix); result[i] = ALPHABET[d[1].intValue()]; value = d[0]; }
        return new String(result);
    }
}
