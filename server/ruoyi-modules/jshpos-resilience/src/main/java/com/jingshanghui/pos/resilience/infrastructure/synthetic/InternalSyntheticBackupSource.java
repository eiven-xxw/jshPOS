package com.jingshanghui.pos.resilience.infrastructure.synthetic;

import com.jingshanghui.pos.resilience.application.port.BackupPorts.Source;
import com.jingshanghui.pos.resilience.domain.BackupModels.DataClass;
import com.jingshanghui.pos.resilience.domain.BackupModels.SourceObject;
import com.jingshanghui.pos.resilience.domain.BackupRules;
import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 仅供显式内部证据模式使用的六类合成备份源。
 *
 * <p>它不读取业务数据库或真实对象，只生成带租户范围摘要和恢复点的低等级合成载荷，
 * 因此只能形成 SYNTHETIC_RESTORE 证据，不能替代生产备份源。</p>
 */
public final class InternalSyntheticBackupSource implements Source {
    private static final String PREFIX = "JSH_SYNTHETIC_RESTORE_V1";

    @Override
    public List<SourceObject> capture(Set<String> tenantIds, Instant pointInTime) {
        if (tenantIds == null || tenantIds.isEmpty() || pointInTime == null) {
            throw new ServiceException("BAK-SYN-001: 合成备份范围或恢复点缺失", 400);
        }
        String scope = BackupRules.tenantScopeSha256(tenantIds);
        return List.of(
            object(DataClass.MYSQL, "mysql/authoritative.synthetic", tenantIds, scope, pointInTime),
            object(DataClass.BUSINESS_OBJECT, "objects/business.synthetic", tenantIds, scope, pointInTime),
            object(DataClass.CONFIG, "config/application.synthetic", tenantIds, scope, pointInTime),
            object(DataClass.TEMPLATE, "templates/store.synthetic", tenantIds, scope, pointInTime),
            object(DataClass.MIGRATION, "migration/checksums.synthetic", tenantIds, scope, pointInTime),
            object(DataClass.EVIDENCE, "evidence/index.synthetic", tenantIds, scope, pointInTime));
    }

    private static SourceObject object(DataClass type, String name, Set<String> tenants,
                                       String scope, Instant pointInTime) {
        String payload = String.join("|", PREFIX, type.name(), scope, pointInTime.toString());
        return new SourceObject(type, name, "application/x-jsh-synthetic-evidence", tenants,
            payload.getBytes(StandardCharsets.UTF_8));
    }
}
