package com.jingshanghui.pos.resilience.infrastructure.persistence;

import com.jingshanghui.pos.resilience.application.port.BackupPorts.Catalog;
import com.jingshanghui.pos.resilience.domain.BackupModels.*;
import com.jingshanghui.pos.resilience.domain.ResilienceIdGenerator;
import com.jingshanghui.pos.resilience.infrastructure.persistence.BackupPersistenceParams.*;
import com.jingshanghui.pos.resilience.infrastructure.persistence.mapper.BackupPersistenceMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** MyBatis XML 备份目录适配器；业务状态迁移只通过具名条件 SQL。 */
@Repository
public class MyBatisBackupCatalogAdapter implements Catalog {
    private final BackupPersistenceMapper mapper;
    private final ResilienceIdGenerator ids;

    public MyBatisBackupCatalogAdapter(BackupPersistenceMapper mapper, ResilienceIdGenerator ids) {
        this.mapper = mapper;
        this.ids = ids;
    }

    @Override public Optional<BackupSet> findBackup(String backupId) {
        BackupRow row = mapper.selectBackup(backupId);
        if (row == null) return Optional.empty();
        List<ObjectDescriptor> objects = mapper.selectObjects(backupId).stream().map(item -> new ObjectDescriptor(
            item.objectId(), DataClass.valueOf(item.dataClass()), item.logicalName(), item.mediaType(),
            item.tenantScopeSha256(), item.plaintextSizeBytes(), item.plaintextSha256(), item.ciphertextSizeBytes(),
            item.ciphertextSha256(), item.keyVersion(), item.nonceBase64(), item.objectKey())).toList();
        return Optional.of(new BackupSet(row.backupId(), row.environmentCode(), csvSet(row.tenantIdsCsv()),
            row.tenantScopeSha256(), row.pointInTime(), row.latestIncludedFactAt(), row.schemaVersion(),
            row.applicationVersion(), row.keyVersion(), row.immutableUntil(), row.requestSha256(),
            nullToEmpty(row.manifestSha256()), nullToEmpty(row.manifestJson()), row.state(), objects));
    }

    @Override @Transactional public void reserve(BackupSet backup, long actorId, String correlationId) {
        int count = mapper.insertBackup(new InsertBackup(backup.backupId(), backup.environment(),
            String.join(",", new TreeSet<>(backup.tenantIds())), backup.tenantScopeSha256(), backup.pointInTime(),
            backup.latestIncludedFactAt(), backup.schemaVersion(), backup.applicationVersion(), backup.keyVersion(),
            backup.immutableUntil(), backup.requestSha256(), actorId, correlationId));
        requireOne(count, "BAK-DB-001: 备份目录登记失败");
        audit(backup.backupId(), null, "BACKUP_RESERVED", actorId, correlationId, backup.requestSha256());
    }

    @Override public void appendObject(String backupId, ObjectDescriptor object) {
        requireOne(mapper.insertObject(new InsertObject(backupId, object.objectId(), object.dataClass().name(),
            object.logicalName(), object.mediaType(), object.tenantScopeSha256(), object.plaintextSizeBytes(),
            object.plaintextSha256(), object.ciphertextSizeBytes(), object.ciphertextSha256(), object.keyVersion(),
            object.nonceBase64(), object.objectKey())), "BAK-DB-002: 对象目录登记失败");
    }

    @Override @Transactional public void complete(BackupSet backup, long actorId, String correlationId) {
        requireOne(mapper.transitionBackup(new TransitionBackup(backup.backupId(), "CREATING", "AVAILABLE",
            backup.manifestSha256(), backup.manifestJson(), null, actorId, correlationId)),
            "BAK-DB-003: 备份完成状态竞争");
        audit(backup.backupId(), null, "BACKUP_AVAILABLE", actorId, correlationId, backup.manifestSha256());
    }

    @Override public void failBackup(String backupId, String reasonSha256, long actorId, String correlationId) {
        if (mapper.transitionBackup(new TransitionBackup(backupId, "CREATING", "FAILED", null, null,
            reasonSha256, actorId, correlationId)) == 1) {
            audit(backupId, null, "BACKUP_FAILED", actorId, correlationId, reasonSha256);
        }
    }

    @Override public Optional<RestoreEvidence> findDrill(String drillId) {
        DrillRow row = mapper.selectDrill(drillId);
        if (row == null) return Optional.empty();
        List<RestoreCheck> checks = mapper.selectChecks(drillId).stream()
            .map(item -> new RestoreCheck(item.checkCode(), item.result(), item.evidenceSha256())).toList();
        return Optional.of(new RestoreEvidence(row.drillId(), row.backupId(), row.requestSha256(), row.startedAt(),
            row.endedAt(), row.rpoSeconds(), row.rtoSeconds(), row.state(), nullToEmpty(row.evidenceSha256()), checks));
    }

    @Override public void startDrill(RestoreEvidence evidence, long actorId, String correlationId) {
        requireOne(mapper.insertDrill(new InsertDrill(evidence.drillId(), evidence.backupId(),
            evidence.requestSha256(), "RUNNING", evidence.startedAt(), actorId, correlationId)),
            "BAK-DB-004: 演练登记失败");
        audit(evidence.backupId(), evidence.drillId(), "RESTORE_STARTED", actorId, correlationId,
            evidence.requestSha256());
    }

    @Override @Transactional public void finishDrill(RestoreEvidence evidence, long actorId, String correlationId) {
        for (RestoreCheck check : evidence.checks()) {
            requireOne(mapper.insertCheck(new InsertCheck(ids.next(), evidence.drillId(), check.code(),
                check.result(), check.evidenceSha256())), "BAK-DB-005: 恢复校验登记失败");
        }
        requireOne(mapper.finishDrill(new FinishDrill(evidence.drillId(), "RUNNING", evidence.result(),
            evidence.endedAt(), evidence.rpoSeconds(), evidence.rtoSeconds(), evidence.evidenceSha256(), actorId,
            correlationId)), "BAK-DB-006: 演练状态竞争");
        audit(evidence.backupId(), evidence.drillId(), "PASS".equals(evidence.result()) ? "RESTORE_PASS" : "RESTORE_FAIL_CLOSED",
            actorId, correlationId, evidence.evidenceSha256());
    }

    private static Set<String> csvSet(String csv) {
        return csv == null || csv.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(csv.split(",")));
    }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private void audit(String backupId, String drillId, String action, long actorId, String correlationId, String evidence) {
        requireOne(mapper.insertAudit(new InsertAudit(ids.next(), backupId, drillId, action, actorId,
            correlationId, evidence)), "BAK-DB-007: 备份审计登记失败");
    }
    private static void requireOne(int count, String message) {
        if (count != 1) throw new ServiceException(message, 409);
    }
}
