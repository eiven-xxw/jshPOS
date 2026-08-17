package com.jingshanghui.pos.resilience.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.resilience.application.port.BackupPorts.AuthorizedScope;
import com.jingshanghui.pos.resilience.application.service.BackupRecoveryService;
import com.jingshanghui.pos.resilience.domain.BackupModels.*;
import com.jingshanghui.pos.resilience.interfaces.rest.dto.BackupRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 备份恢复管理API；租户范围只来自受权平台适配器，响应不返回密钥、明文或对象内容。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class BackupController {
    private final BackupRecoveryService service;
    private final AuthorizedScope scope;

    @PostMapping("/backups")
    @SaCheckPermission("backup:create")
    @Log(title="创建加密备份", businessType=BusinessType.INSERT, isSaveRequestData=false, isSaveResponseData=false)
    public R<BackupSummary> create(@Valid @RequestBody BackupRequests.Create request) {
        BackupSet backup = service.create(new CreateBackup(request.backupId(), request.environment(), scope.tenantIds(),
            request.pointInTime(), request.latestIncludedFactAt(), request.schemaVersion(),
            request.applicationVersion(), request.keyVersion(), request.immutableUntil(), LoginHelper.getUserId(),
            request.correlationId()));
        return R.ok(summary(backup));
    }

    @GetMapping("/backups/{backupId}")
    @SaCheckPermission("backup:catalog:read")
    public R<BackupSummary> get(@PathVariable @Pattern(regexp=BackupRequests.ULID) String backupId) {
        return R.ok(summary(service.getBackup(backupId, scope.tenantIds())));
    }

    @PostMapping("/backups/{backupId}/restore-drills")
    @SaCheckPermission("backup:restore:execute")
    @Log(title="执行空环境恢复演练", businessType=BusinessType.UPDATE, isSaveRequestData=false, isSaveResponseData=false)
    public R<RestoreSummary> restore(@PathVariable @Pattern(regexp=BackupRequests.ULID) String backupId,
                                     @Valid @RequestBody BackupRequests.Restore request) {
        return R.ok(restoreSummary(service.restore(new RestoreBackup(request.drillId(), backupId, scope.tenantIds(),
            request.expectedSchemaVersion(), LoginHelper.getUserId(), request.correlationId()))));
    }

    @GetMapping("/restore-drills/{drillId}")
    @SaCheckPermission("backup:evidence:read")
    public R<RestoreSummary> drill(@PathVariable @Pattern(regexp=BackupRequests.ULID) String drillId) {
        return R.ok(restoreSummary(service.getDrill(drillId, scope.tenantIds())));
    }

    /** @param backupId 备份ULID @param state 状态 @param tenantScopeSha256 租户范围摘要 @param pointInTime 恢复点 @param immutableUntil 保留截止 @param manifestSha256 清单摘要 @param objectCount 对象数 */
    public record BackupSummary(String backupId, String state, String tenantScopeSha256,
                                java.time.Instant pointInTime, java.time.Instant immutableUntil,
                                String manifestSha256, int objectCount) { }
    /** @param drillId 演练ULID @param backupId 备份ULID @param state 状态 @param rpoSeconds RPO秒 @param rtoSeconds RTO秒 @param evidenceSha256 证据摘要 @param checkCount 校验数 */
    public record RestoreSummary(String drillId, String backupId, String state, long rpoSeconds, long rtoSeconds,
                                 String evidenceSha256, int checkCount) { }

    private static BackupSummary summary(BackupSet item) {
        return new BackupSummary(item.backupId(), item.state(), item.tenantScopeSha256(), item.pointInTime(),
            item.immutableUntil(), item.manifestSha256(), item.objects().size());
    }
    private static RestoreSummary restoreSummary(RestoreEvidence item) {
        return new RestoreSummary(item.drillId(), item.backupId(), item.result(), item.rpoSeconds(),
            item.rtoSeconds(), item.evidenceSha256(), item.checks().size());
    }
}
