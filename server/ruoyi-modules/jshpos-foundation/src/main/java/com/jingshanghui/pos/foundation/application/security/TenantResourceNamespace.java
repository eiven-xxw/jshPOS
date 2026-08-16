package com.jingshanghui.pos.foundation.application.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 缓存、导出与对象存储统一租户命名空间。调用方不能自行拼接租户前缀。
 */
@Service
@RequiredArgsConstructor
public class TenantResourceNamespace {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private final TrustedTenantContext tenantContext;

    public String cacheKey(String key) {
        return build("cache", key);
    }

    public String exportKey(String key) {
        return build("export", key);
    }

    public String objectKey(String key) {
        return build("object", key);
    }

    private String build(String type, String key) {
        if (key == null || !SAFE_SEGMENT.matcher(key).matches()) {
            throw new ServiceException("FND-IAM-005: 资源标识不安全", 400);
        }
        return "tenant/" + tenantContext.requireTenantId() + "/" + type + "/" + key;
    }
}
