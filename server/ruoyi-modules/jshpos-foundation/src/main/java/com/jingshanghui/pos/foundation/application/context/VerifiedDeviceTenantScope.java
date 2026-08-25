package com.jingshanghui.pos.foundation.application.context;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 已通过设备凭据验证后的最小租户查询作用域。
 *
 * <p>该作用域只允许终端认证流程在尚未建立员工会话时读取绑定门店，作用域在调用结束后必定清理，
 * 不能替代普通业务请求的 {@link TrustedTenantContext}。</p>
 */
@Component
public class VerifiedDeviceTenantScope {

    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$");
    private final ThreadLocal<DeviceIdentity> current = new ThreadLocal<>();

    /** 在当前线程内临时绑定已验真的设备身份，并确保异常路径也清理上下文。 */
    public <T> T execute(DeviceIdentity identity, Supplier<T> action) {
        requireValid(identity);
        Objects.requireNonNull(action, "action");
        if (current.get() != null) {
            throw new ServiceException("TRM_DEVICE_SCOPE_NESTED: 设备认证作用域禁止嵌套", 409);
        }
        current.set(identity);
        try {
            return action.get();
        } finally {
            current.remove();
        }
    }

    /** Mapper 守卫只在该最小作用域存在时放行绑定门店查询。 */
    public boolean isActive() {
        return current.get() != null;
    }

    /** 校验查询参数必须与已验真的设备绑定完全一致。 */
    public void requireMatches(String tenantId, Long orgUnitId, Long storeId) {
        DeviceIdentity identity = current.get();
        if (identity == null) {
            throw new ServiceException("TRM_DEVICE_SCOPE_MISSING: 缺少已验证设备上下文", 401);
        }
        if (!identity.tenantId().equals(tenantId) || !identity.orgUnitId().equals(orgUnitId)
            || !identity.storeId().equals(storeId)) {
            throw new ServiceException("TRM_DEVICE_SCOPE_MISMATCH: 设备绑定上下文不一致", 403);
        }
    }

    private void requireValid(DeviceIdentity identity) {
        if (identity == null || identity.tenantId() == null || !TENANT_ID.matcher(identity.tenantId()).matches()
            || identity.orgUnitId() == null || identity.orgUnitId() <= 0
            || identity.storeId() == null || identity.storeId() <= 0
            || identity.deviceId() == null || identity.deviceId().isBlank()) {
            throw new ServiceException("TRM_DEVICE_SCOPE_INVALID: 已验证设备上下文无效", 401);
        }
    }

    /** 只包含服务端设备注册表已经验真的租户、组织、门店与设备身份。 */
    public record DeviceIdentity(String tenantId, Long orgUnitId, Long storeId, String deviceId) {
    }
}
