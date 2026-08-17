package com.jingshanghui.pos.member.infrastructure.crypto;

import com.jingshanghui.pos.member.application.port.MemberIdentityProtector;
import org.dromara.common.core.exception.ServiceException;

/** 未配置外部密钥时失败关闭，绝不退化为明文或固定测试密钥。 */
public final class RejectingMemberIdentityProtector implements MemberIdentityProtector {
    private ServiceException unavailable() {
        return new ServiceException("MEM-CRYPTO-004: 会员身份密钥未由安全环境提供", 503);
    }
    @Override public ProtectedIdentity protect(String identityType, String normalizedValue) { throw unavailable(); }
    @Override public String lookupHmac(String identityType, String normalizedValue) { throw unavailable(); }
}
