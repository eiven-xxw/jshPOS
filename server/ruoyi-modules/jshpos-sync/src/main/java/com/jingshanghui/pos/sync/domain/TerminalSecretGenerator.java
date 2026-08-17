package com.jingshanghui.pos.sync.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/** 生成 256 bit 一次性终端秘密，不包含可读业务信息。 */
@Component
public class TerminalSecretGenerator {
    private final SecureRandom random = new SecureRandom();

    public String next() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
