package com.jingshanghui.pos.foundation.application.context;

import java.util.Optional;

/**
 * 身份提供端口。不得由 HTTP body、query、path 或普通 header 实现。
 */
@FunctionalInterface
public interface TrustedPrincipalSource {

    Optional<TrustedPrincipal> current();
}
