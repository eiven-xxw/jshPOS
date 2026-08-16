package com.jingshanghui.pos.foundation.infrastructure.observability;

import org.slf4j.MDC;

/**
 * 关联标识读取器。领域审计只从服务端过滤器建立的 MDC 读取。
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? "internal-no-http-context" : value;
    }
}
