package com.jingshanghui.pos.service.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;

/** 服务运营事实 ULID 生成器；ID 不包含租户、门店或个人信息。 */
@Component
public class ServiceIdGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ServiceIdGenerator(Clock clock) { this.clock = clock; }

    public String next() {
        long time = clock.millis(); char[] result = new char[26];
        for (int i = 9; i >= 0; i--) { result[i] = ALPHABET[(int) (time & 31)]; time >>>= 5; }
        for (int i = 10; i < result.length; i++) result[i] = ALPHABET[random.nextInt(32)];
        return new String(result);
    }
}
