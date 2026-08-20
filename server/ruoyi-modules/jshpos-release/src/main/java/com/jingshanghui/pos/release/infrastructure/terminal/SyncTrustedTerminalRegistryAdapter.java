package com.jingshanghui.pos.release.infrastructure.terminal;

import com.jingshanghui.pos.release.application.port.ReleasePorts.TrustedTerminalRegistry;
import com.jingshanghui.pos.release.domain.ReleaseModels.TrustedTerminal;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalView;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/** 从 Gate 6A 终端注册表读取可信身份；Android系统版本未登记时保持空并在兼容门禁失败关闭。 */
@Component
public class SyncTrustedTerminalRegistryAdapter implements TrustedTerminalRegistry {
    private final TerminalRegistryPort registry;
    public SyncTrustedTerminalRegistryAdapter(TerminalRegistryPort registry) { this.registry = registry; }

    @Override public TrustedTerminal require(String tenantId, String deviceId) {
        TerminalView value = registry.findDevice(tenantId, deviceId);
        if (value == null) throw new ServiceException("UPG-TRM-003: 终端不存在或跨租户", 404);
        return new TrustedTerminal(tenantId, value.deviceId(), value.storeId(), value.status(), value.appVersion(),
            value.minProtocolVersion(), value.maxProtocolVersion(), value.schemaVersion(), null,
            value.capabilitySha256());
    }
}
