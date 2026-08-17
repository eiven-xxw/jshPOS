package com.jingshanghui.pos.member.config;

import com.jingshanghui.pos.member.application.port.MemberIdentityProtector;
import com.jingshanghui.pos.member.infrastructure.crypto.AesGcmMemberIdentityProtector;
import com.jingshanghui.pos.member.infrastructure.crypto.RejectingMemberIdentityProtector;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.util.Base64;

/** Gate 5C 会员独立模块入口，不修改 RuoYi 系统模块。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.member")
@MapperScan("com.jingshanghui.pos.member.infrastructure.persistence.mapper")
public class MemberAutoConfiguration {
    /** 从运行环境接收外部密钥；缺失或非法均失败关闭身份能力。 */
    @Bean
    public MemberIdentityProtector memberIdentityProtector(Environment environment) {
        String lookup = environment.getProperty("JSH_MEMBER_LOOKUP_KEY_B64");
        String encryption = environment.getProperty("JSH_MEMBER_ENCRYPTION_KEY_B64");
        String version = environment.getProperty("JSH_MEMBER_KEY_VERSION");
        if (lookup == null || encryption == null || version == null) return new RejectingMemberIdentityProtector();
        try {
            return new AesGcmMemberIdentityProtector(Base64.getDecoder().decode(lookup),
                Base64.getDecoder().decode(encryption), Integer.parseInt(version));
        } catch (RuntimeException exception) {
            return new RejectingMemberIdentityProtector();
        }
    }
}
