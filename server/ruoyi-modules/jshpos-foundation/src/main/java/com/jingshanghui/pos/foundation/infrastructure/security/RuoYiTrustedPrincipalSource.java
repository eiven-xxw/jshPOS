package com.jingshanghui.pos.foundation.infrastructure.security;

import org.dromara.common.satoken.utils.LoginHelper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipalSource;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 只读取 Sa-Token 已签发会话中的身份，不读取请求参数或租户 header。
 */
@Component
public class RuoYiTrustedPrincipalSource implements TrustedPrincipalSource {

    @Override
    public Optional<TrustedPrincipal> current() {
        if (!LoginHelper.isLogin()) {
            return Optional.empty();
        }
        return Optional.of(new TrustedPrincipal(
            LoginHelper.getTenantId(),
            LoginHelper.getUserId(),
            LoginHelper.getDeptId(),
            LoginHelper.getUsername()
        ));
    }
}
