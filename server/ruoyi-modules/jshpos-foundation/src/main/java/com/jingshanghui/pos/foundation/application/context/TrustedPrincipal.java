package com.jingshanghui.pos.foundation.application.context;

/**
 * 来自认证会话或受控内部执行器的可信主体快照。
 */
public record TrustedPrincipal(
    String tenantId,
    Long userId,
    Long deptId,
    String username
) {
}
