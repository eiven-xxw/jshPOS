package com.jingshanghui.pos.integration.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部边界的显式失败关闭注册表。
 *
 * <p>该注册表只暴露真实状态，不提供模拟成功路径，也不会发起网络或设备命令。</p>
 */
public final class ExternalBoundaryRegistry {
    private final Map<String, BoundaryState> states;

    public ExternalBoundaryRegistry() {
        Map<String, BoundaryState> configured = new LinkedHashMap<>();
        configured.put("payment-provider", new BoundaryState("T2-PAY-002", "BLOCKED", "UNAVAILABLE"));
        configured.put("real-hardware", new BoundaryState("T2-HWD-001", "BLOCKED", "UNAVAILABLE"));
        configured.put("design-partner", new BoundaryState("T2-PAR-001", "BLOCKED", "UNAVAILABLE"));
        configured.put("real-printer", new BoundaryState("T2-PRN-001", "BLOCKED", "UNAVAILABLE"));
        configured.put("jsh-connector", new BoundaryState("T2-JSH-001", "DEFERRED", "UNAVAILABLE"));
        this.states = Map.copyOf(configured);
    }

    public Map<String, BoundaryState> snapshot() {
        return states;
    }

    /** 外部 Requirement 状态与运行时可用性，两者均不可由内部模拟证据改绿。 */
    public record BoundaryState(String requirementId, String governanceStatus, String runtimeStatus) {
    }
}
