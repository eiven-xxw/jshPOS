package com.jingshanghui.pos.resilience.application.service;

import com.jingshanghui.pos.resilience.application.port.BackupPorts.*;
import com.jingshanghui.pos.resilience.domain.*;
import com.jingshanghui.pos.resilience.domain.BackupModels.*;
import com.jingshanghui.pos.resilience.infrastructure.security.AesGcmBackupCipher;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * BAK Owner 应用服务。对象写入在外部事务中只追加，目录先登记 CREATING，失败会保留可审计失败状态。
 */
@Service
public class BackupRecoveryService {
    private final Catalog catalog;
    private final ObjectStore objectStore;
    private final KeyProvider keyProvider;
    private final Source source;
    private final RestoreTarget restoreTarget;
    private final AesGcmBackupCipher cipher;
    private final ResilienceIdGenerator idGenerator;
    private final Clock clock;

    public BackupRecoveryService(Catalog catalog, ObjectStore objectStore, KeyProvider keyProvider, Source source,
                                 RestoreTarget restoreTarget, AesGcmBackupCipher cipher,
                                 ResilienceIdGenerator idGenerator, Clock clock) {
        this.catalog = catalog;
        this.objectStore = objectStore;
        this.keyProvider = keyProvider;
        this.source = source;
        this.restoreTarget = restoreTarget;
        this.cipher = cipher;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public BackupSet create(CreateBackup command) {
        Instant now = clock.instant();
        BackupRules.validateCreate(command, now);
        CreateBackup effective = normalizePersistencePrecision(command);
        BackupRules.validateCreate(effective, now);
        String requestSha = requestSha(effective);
        Optional<BackupSet> existing = catalog.findBackup(effective.backupId());
        if (existing.isPresent()) {
            if (!existing.get().requestSha256().equals(requestSha)) conflict();
            return existing.get();
        }
        String scopeSha = BackupRules.tenantScopeSha256(effective.tenantIds());
        BackupSet reserved = new BackupSet(effective.backupId(), effective.environment(), effective.tenantIds(), scopeSha,
            effective.pointInTime(), effective.latestIncludedFactAt(), effective.schemaVersion(), effective.applicationVersion(),
            effective.keyVersion(), effective.immutableUntil(), requestSha, "", "", "CREATING", List.of());
        catalog.reserve(reserved, effective.requestedBy(), effective.correlationId());
        try {
            List<SourceObject> sourceObjects = source.capture(effective.tenantIds(), effective.pointInTime());
            BackupRules.validateSourceObjects(effective.tenantIds(), sourceObjects);
            var key = keyProvider.resolve(effective.keyVersion());
            List<ObjectDescriptor> descriptors = new ArrayList<>(sourceObjects.size());
            for (SourceObject item : sourceObjects) {
                byte[] plaintext = item.content();
                String plainSha = BackupRules.sha256(plaintext);
                String aad = aad(effective.backupId(), scopeSha, item.logicalName(), plainSha, effective.keyVersion());
                AesGcmBackupCipher.Encrypted encrypted = cipher.encrypt(plaintext, key, aad);
                String cipherSha = BackupRules.sha256(encrypted.bytes());
                String objectKey = "backups/" + scopeSha + "/" + effective.backupId() + "/" + cipherSha + ".aead";
                objectStore.putNew(objectKey, encrypted.bytes(), effective.immutableUntil());
                ObjectDescriptor descriptor = new ObjectDescriptor(idGenerator.next(), item.dataClass(),
                    item.logicalName(), item.mediaType(), scopeSha, plaintext.length, plainSha,
                    encrypted.bytes().length, cipherSha, effective.keyVersion(), encrypted.nonceBase64(), objectKey);
                catalog.appendObject(effective.backupId(), descriptor);
                descriptors.add(descriptor);
            }
            BackupSet beforeManifest = new BackupSet(effective.backupId(), effective.environment(), effective.tenantIds(),
                scopeSha, effective.pointInTime(), effective.latestIncludedFactAt(), effective.schemaVersion(),
                effective.applicationVersion(), effective.keyVersion(), effective.immutableUntil(), requestSha, "", "",
                "AVAILABLE", descriptors);
            String manifestJson = new String(CanonicalBackupManifest.encode(beforeManifest), StandardCharsets.UTF_8);
            BackupSet completed = new BackupSet(beforeManifest.backupId(), beforeManifest.environment(),
                beforeManifest.tenantIds(), beforeManifest.tenantScopeSha256(), beforeManifest.pointInTime(),
                beforeManifest.latestIncludedFactAt(), beforeManifest.schemaVersion(), beforeManifest.applicationVersion(),
                beforeManifest.keyVersion(), beforeManifest.immutableUntil(), requestSha,
                BackupRules.sha256(manifestJson.getBytes(StandardCharsets.UTF_8)), manifestJson, "AVAILABLE", descriptors);
            catalog.complete(completed, effective.requestedBy(), effective.correlationId());
            return completed;
        } catch (RuntimeException exception) {
            catalog.failBackup(effective.backupId(), BackupRules.sha256(exception.getClass().getName()
                .getBytes(StandardCharsets.UTF_8)), effective.requestedBy(), effective.correlationId());
            throw exception;
        }
    }

    public RestoreEvidence restore(RestoreBackup command) {
        String requestSha = restoreRequestSha(command);
        Optional<RestoreEvidence> previous = catalog.findDrill(command.drillId());
        if (previous.isPresent()) {
            if (!previous.get().requestSha256().equals(requestSha)) conflict();
            return previous.get();
        }
        BackupSet backup = catalog.findBackup(command.backupId())
            .orElseThrow(() -> new ServiceException("BAK-RST-007: 备份不存在", 404));
        BackupRules.validateRestore(command, backup);
        Instant started = clock.instant();
        RestoreEvidence running = new RestoreEvidence(command.drillId(), command.backupId(), requestSha, started,
            null, 0, 0, "RUNNING", "", List.of());
        catalog.startDrill(running, command.requestedBy(), command.correlationId());
        List<RestoreCheck> checks = new ArrayList<>();
        boolean targetStarted = false;
        try {
            String recalculatedManifest = BackupRules.sha256(CanonicalBackupManifest.encode(new BackupSet(
                backup.backupId(), backup.environment(), backup.tenantIds(), backup.tenantScopeSha256(),
                backup.pointInTime(), backup.latestIncludedFactAt(), backup.schemaVersion(), backup.applicationVersion(),
                backup.keyVersion(), backup.immutableUntil(), backup.requestSha256(), "", "", backup.state(), backup.objects())));
            requireDigest(recalculatedManifest, backup.manifestSha256(), "BAK-RST-008: 清单摘要不匹配");
            pass(checks, "MANIFEST", recalculatedManifest);
            var key = keyProvider.resolve(backup.keyVersion());
            List<RestoredObject> plaintexts = new ArrayList<>(backup.objects().size());
            for (ObjectDescriptor object : backup.objects()) {
                if (!backup.tenantScopeSha256().equals(object.tenantScopeSha256())) {
                    throw new ServiceException("BAK-SEC-004: 对象租户摘要被替换", 403);
                }
                byte[] encrypted = objectStore.get(object.objectKey());
                requireDigest(BackupRules.sha256(encrypted), object.ciphertextSha256(), "BAK-RST-009: 密文摘要不匹配");
                String aad = aad(backup.backupId(), backup.tenantScopeSha256(), object.logicalName(),
                    object.plaintextSha256(), object.keyVersion());
                byte[] plaintext = cipher.decrypt(encrypted, key, aad, object.nonceBase64());
                requireDigest(BackupRules.sha256(plaintext), object.plaintextSha256(), "BAK-RST-010: 明文摘要不匹配");
                plaintexts.add(new RestoredObject(object.dataClass(), object.logicalName(), plaintext));
            }
            pass(checks, "SHA256", BackupRules.sha256((backup.backupId() + backup.objects().size()).getBytes(StandardCharsets.UTF_8)));
            pass(checks, "ENCRYPTION", BackupRules.sha256(backup.keyVersion().getBytes(StandardCharsets.UTF_8)));
            restoreTarget.beginEmpty(command.drillId(), command.expectedTenantIds());
            targetStarted = true;
            for (RestoredObject object : plaintexts) restoreTarget.restore(object.dataClass(), object.logicalName(), object.content());
            Reconciliation reconciliation = restoreTarget.validateAndReconcile(backup.schemaVersion(), backup.pointInTime());
            BackupRules.requireSuccessfulReconciliation(reconciliation);
            pass(checks, "FLYWAY_VALIDATE", backup.manifestSha256());
            pass(checks, "PROJECTION_REBUILD", backup.manifestSha256());
            pass(checks, "TENANT_RECONCILIATION", backup.tenantScopeSha256());
            pass(checks, "BUSINESS_DAY_RECONCILIATION", backup.manifestSha256());
            pass(checks, "CURSOR", backup.manifestSha256());
            pass(checks, "AUDIT", backup.manifestSha256());
            restoreTarget.complete();
            Instant ended = clock.instant();
            long rpo = BackupRules.rpoSeconds(backup);
            long rto = BackupRules.rtoSeconds(started, ended);
            if (rpo > BackupRules.MAX_RPO_SECONDS || rto > BackupRules.MAX_RTO_SECONDS) {
                throw new ServiceException("BAK-RST-011: Alpha候选RPO或RTO未达到", 422);
            }
            String evidenceSha = evidenceSha(command, backup, started, ended, rpo, rto, checks, "PASS");
            RestoreEvidence passed = new RestoreEvidence(command.drillId(), command.backupId(), requestSha,
                started, ended, rpo, rto, "PASS", evidenceSha, checks);
            catalog.finishDrill(passed, command.requestedBy(), command.correlationId());
            return passed;
        } catch (RuntimeException exception) {
            if (targetStarted) restoreTarget.abort();
            Instant ended = clock.instant();
            long rpo = Math.max(0, BackupRules.rpoSeconds(backup));
            long rto = BackupRules.rtoSeconds(started, ended);
            String evidenceSha = evidenceSha(command, backup, started, ended, rpo, rto, checks, "FAIL_CLOSED");
            catalog.finishDrill(new RestoreEvidence(command.drillId(), command.backupId(), requestSha, started,
                ended, rpo, rto, "FAIL_CLOSED", evidenceSha, checks), command.requestedBy(), command.correlationId());
            throw exception;
        }
    }

    public BackupSet getBackup(String backupId, Set<String> authorizedTenantIds) {
        BackupSet backup = catalog.findBackup(backupId)
            .orElseThrow(() -> new ServiceException("BAK-QRY-001: 备份不存在或不在授权范围", 404));
        requireAuthorizedScope(backup, authorizedTenantIds);
        return backup;
    }

    public RestoreEvidence getDrill(String drillId, Set<String> authorizedTenantIds) {
        RestoreEvidence evidence = catalog.findDrill(drillId)
            .orElseThrow(() -> new ServiceException("BAK-QRY-002: 演练不存在或不在授权范围", 404));
        BackupSet backup = catalog.findBackup(evidence.backupId())
            .orElseThrow(() -> new ServiceException("BAK-QRY-003: 演练关联备份不存在", 404));
        requireAuthorizedScope(backup, authorizedTenantIds);
        return evidence;
    }

    private static void requireAuthorizedScope(BackupSet backup, Set<String> authorizedTenantIds) {
        if (authorizedTenantIds == null || !backup.tenantIds().equals(Set.copyOf(authorizedTenantIds))) {
            throw new ServiceException("BAK-QRY-004: 备份不在授权租户范围", 404);
        }
    }

    private record RestoredObject(DataClass dataClass, String logicalName, byte[] content) {
        private RestoredObject { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }

    private static void pass(List<RestoreCheck> checks, String code, String evidence) {
        checks.add(new RestoreCheck(code, "PASS", BackupRules.sha256(evidence.getBytes(StandardCharsets.UTF_8))));
    }

    private static void requireDigest(String actual, String expected, String message) {
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new ServiceException(message, 422);
        }
    }

    private static String aad(String backupId, String scope, String logical, String sha, String keyVersion) {
        return String.join("|", "JSH-BAK-V1", backupId, scope, logical, sha, keyVersion);
    }

    private static String requestSha(CreateBackup command) {
        return BackupRules.sha256(String.join("|", command.backupId(), command.environment(),
            String.join(",", new TreeSet<>(command.tenantIds())), command.pointInTime().toString(),
            command.latestIncludedFactAt().toString(), command.schemaVersion(), command.applicationVersion(),
            command.keyVersion(), command.immutableUntil().toString(), Long.toString(command.requestedBy()),
            command.correlationId()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * MySQL 备份目录使用 DATETIME(3)。在形成幂等摘要、源快照和规范清单之前统一到毫秒，
     * 避免创建进程中的纳秒值与数据库回读值不同而破坏恢复清单的可重算性。
     */
    private static CreateBackup normalizePersistencePrecision(CreateBackup command) {
        return new CreateBackup(command.backupId(), command.environment(), command.tenantIds(),
            command.pointInTime().truncatedTo(ChronoUnit.MILLIS),
            command.latestIncludedFactAt().truncatedTo(ChronoUnit.MILLIS), command.schemaVersion(),
            command.applicationVersion(), command.keyVersion(),
            command.immutableUntil().truncatedTo(ChronoUnit.MILLIS), command.requestedBy(), command.correlationId());
    }

    private static String restoreRequestSha(RestoreBackup command) {
        return BackupRules.sha256(String.join("|", command.drillId(), command.backupId(),
            String.join(",", new TreeSet<>(command.expectedTenantIds())), command.expectedSchemaVersion(),
            Long.toString(command.requestedBy()), command.correlationId()).getBytes(StandardCharsets.UTF_8));
    }

    private static String evidenceSha(RestoreBackup command, BackupSet backup, Instant started, Instant ended,
                                      long rpo, long rto, List<RestoreCheck> checks, String result) {
        return BackupRules.sha256((command.drillId() + '|' + backup.manifestSha256() + '|' + started + '|' + ended
            + '|' + rpo + '|' + rto + '|' + result + '|' + checks).getBytes(StandardCharsets.UTF_8));
    }

    private static void conflict() { throw new ServiceException("BAK-IDEM-001: 同幂等键内容不一致", 409); }
}
