package com.jingshanghui.pos.resilience.application.service;

import com.jingshanghui.pos.resilience.application.port.BackupPorts.*;
import com.jingshanghui.pos.resilience.domain.*;
import com.jingshanghui.pos.resilience.domain.BackupModels.*;
import com.jingshanghui.pos.resilience.infrastructure.security.AesGcmBackupCipher;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** 完整加密备份、空环境恢复、幂等、损坏和部分失败收敛回归。 */
class BackupRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final Set<String> TENANTS = Set.of("TENANT_A", "TENANT_B");

    @Test void createsEncryptedSetRestoresFromEmptyAndReplaysIdempotently() {
        Fixture fixture = new Fixture();
        CreateBackup create = create("synthetic");
        BackupSet backup = fixture.service.create(create);
        assertThat(backup.state()).isEqualTo("AVAILABLE");
        assertThat(backup.objects()).hasSize(6);
        assertThat(fixture.store.bytes).hasSize(6).allSatisfy((key, value) -> assertThat(value.length).isGreaterThan(20));
        assertThat(fixture.service.create(create)).isSameAs(backup);
        assertThatThrownBy(() -> fixture.service.create(create("changed"))).isInstanceOf(ServiceException.class).hasMessageContaining("幂等");

        RestoreBackup restore = new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 9, ulid(3));
        RestoreEvidence evidence = fixture.service.restore(restore);
        assertThat(evidence.result()).isEqualTo("PASS");
        assertThat(evidence.rpoSeconds()).isEqualTo(300);
        assertThat(evidence.rtoSeconds()).isEqualTo(1);
        assertThat(evidence.checks()).extracting(RestoreCheck::code).containsExactly(
            "MANIFEST","SHA256","ENCRYPTION","FLYWAY_VALIDATE","PROJECTION_REBUILD",
            "TENANT_RECONCILIATION","BUSINESS_DAY_RECONCILIATION","CURSOR","AUDIT");
        assertThat(fixture.target.restored).hasSize(6);
        assertThat(fixture.service.restore(restore)).isSameAs(evidence);
        assertThatThrownBy(() -> fixture.service.restore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "38", 9, ulid(3))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("幂等");
        assertThat(fixture.service.getBackup(ulid(1), TENANTS)).isSameAs(backup);
        assertThat(fixture.service.getDrill(ulid(2), TENANTS)).isSameAs(evidence);
        assertThatThrownBy(() -> fixture.service.getBackup(ulid(1), Set.of("TENANT_A")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("授权租户范围");
        assertThatThrownBy(() -> fixture.service.getDrill(ulid(2), Set.of("TENANT_A")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("授权租户范围");
        writeSyntheticDrillEvidenceIfRequested(evidence, fixture.target.validatedFactRows);
    }

    @Test void failsClosedForCorruptObjectAndReconciliationDifference() {
        Fixture corrupt = new Fixture();
        BackupSet backup = corrupt.service.create(create("synthetic"));
        String firstKey = backup.objects().get(0).objectKey();
        corrupt.store.bytes.get(firstKey)[0] ^= 1;
        assertThatThrownBy(() -> corrupt.service.restore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 9, ulid(3))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("密文摘要");
        assertThat(corrupt.catalog.drills.get(ulid(2)).result()).isEqualTo("FAIL_CLOSED");

        Fixture mismatch = new Fixture();
        mismatch.service.create(create("synthetic"));
        mismatch.target.reconciliation = new Reconciliation(true, true, 0, 1, 0, 0);
        assertThatThrownBy(() -> mismatch.service.restore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 9, ulid(3))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("守恒");
        assertThat(mismatch.target.aborted).isTrue();
        assertThat(mismatch.catalog.drills.get(ulid(2)).result()).isEqualTo("FAIL_CLOSED");
    }

    @Test void marksInterruptedCaptureFailedWithoutGreenEvidence() {
        Fixture fixture = new Fixture();
        fixture.source.fail = true;
        assertThatThrownBy(() -> fixture.service.create(create("synthetic"))).isInstanceOf(ServiceException.class).hasMessageContaining("synthetic interruption");
        assertThat(fixture.catalog.backups.get(ulid(1)).state()).isEqualTo("FAILED");
        assertThat(fixture.catalog.drills).isEmpty();
    }

    @Test void normalizesBackupTimesToMysqlPrecisionBeforeCreatingManifestAndSourceSnapshot() {
        Fixture fixture = new Fixture();
        fixture.catalog.truncateTimesOnRead = true;
        Instant pointInTime = NOW.minusSeconds(1).plusNanos(456_789);
        CreateBackup command = new CreateBackup(ulid(1), "synthetic", TENANTS, pointInTime,
            pointInTime.minusSeconds(300).plusNanos(123_456), "39", "1.0.0", "synthetic-v1",
            pointInTime.plusSeconds(86_400).plusNanos(654_321), 9, ulid(3));

        BackupSet created = fixture.service.create(command);

        assertThat(created.pointInTime()).isEqualTo(pointInTime.truncatedTo(ChronoUnit.MILLIS));
        assertThat(created.latestIncludedFactAt())
            .isEqualTo(command.latestIncludedFactAt().truncatedTo(ChronoUnit.MILLIS));
        assertThat(created.immutableUntil()).isEqualTo(command.immutableUntil().truncatedTo(ChronoUnit.MILLIS));
        assertThat(fixture.source.capturedPoint).isEqualTo(created.pointInTime());
        assertThat(fixture.service.restore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 9, ulid(3))).result())
            .isEqualTo("PASS");
    }

    private static CreateBackup create(String environment) {
        return new CreateBackup(ulid(1), environment, TENANTS, NOW, NOW.minusSeconds(300), "39", "1.0.0",
            "synthetic-v1", NOW.plusSeconds(86400), 9, ulid(3));
    }
    private static String ulid(int last) { return "01K2A00000000000000000000" + last; }

    /** CI 仅在显式指定路径时输出低等级合成恢复证据，不把单元夹具提升为云灾备证据。 */
    private static void writeSyntheticDrillEvidenceIfRequested(RestoreEvidence evidence, long factRows) {
        String requested = System.getProperty("gate6a.restore.output", "").trim();
        if (requested.isEmpty()) return;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int row = 0; row < factRows; row++) {
                digest.update(("SYNTHETIC-FACT-" + row + '\n').getBytes(StandardCharsets.UTF_8));
            }
            String factDigest = HexFormat.of().formatHex(digest.digest());
            String checks = evidence.checks().stream().map(check -> "\"" + check.code() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
            String json = """
                {
                  "schemaVersion":"1.0",
                  "requirement":"T2-BAK-001",
                  "evidenceLevel":"SYNTHETIC_RESTORE",
                  "drillId":"%s",
                  "backupId":"%s",
                  "isolatedEnvironment":true,
                  "startedAt":"%s",
                  "endedAt":"%s",
                  "rpoSeconds":%d,
                  "rtoSeconds":%d,
                  "checks":[%s],
                  "result":"%s",
                  "syntheticFactRows":%d,
                  "syntheticFactDigestSha256":"%s",
                  "providerNetworkCalls":0,
                  "realDeviceCommands":0,
                  "cloudDrEvidence":0,
                  "commercialSla":false,
                  "evidenceSha256":"%s"
                }
                """.formatted(evidence.drillId(), evidence.backupId(), evidence.startedAt(), evidence.endedAt(),
                evidence.rpoSeconds(), evidence.rtoSeconds(), checks, evidence.result(), factRows, factDigest,
                evidence.evidenceSha256());
            Path output = Path.of(requested).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            Files.writeString(output, json, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("无法生成Gate 6A合成恢复证据", exception);
        }
    }

    private static final class Fixture {
        final MemoryCatalog catalog = new MemoryCatalog();
        final MemoryStore store = new MemoryStore();
        final SyntheticSource source = new SyntheticSource();
        final SyntheticTarget target = new SyntheticTarget();
        final BackupRecoveryService service = new BackupRecoveryService(catalog, store,
            version -> new SecretKeySpec(new byte[32], "AES"), source, target, new AesGcmBackupCipher(),
            new ResilienceIdGenerator(Clock.fixed(NOW, ZoneOffset.UTC)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class MemoryCatalog implements Catalog {
        final Map<String,BackupSet> backups = new HashMap<>();
        final Map<String,RestoreEvidence> drills = new HashMap<>();
        boolean truncateTimesOnRead;
        @Override public Optional<BackupSet> findBackup(String id) {
            BackupSet backup = backups.get(id);
            if (backup == null || !truncateTimesOnRead) return Optional.ofNullable(backup);
            return Optional.of(new BackupSet(backup.backupId(), backup.environment(), backup.tenantIds(),
                backup.tenantScopeSha256(), backup.pointInTime().truncatedTo(ChronoUnit.MILLIS),
                backup.latestIncludedFactAt().truncatedTo(ChronoUnit.MILLIS), backup.schemaVersion(),
                backup.applicationVersion(), backup.keyVersion(), backup.immutableUntil().truncatedTo(ChronoUnit.MILLIS),
                backup.requestSha256(), backup.manifestSha256(), backup.manifestJson(), backup.state(), backup.objects()));
        }
        @Override public void reserve(BackupSet item,long actor,String correlation) { backups.put(item.backupId(),item); }
        @Override public void appendObject(String id,ObjectDescriptor object) {
            BackupSet old=backups.get(id); List<ObjectDescriptor> list=new ArrayList<>(old.objects()); list.add(object);
            backups.put(id,new BackupSet(old.backupId(),old.environment(),old.tenantIds(),old.tenantScopeSha256(),old.pointInTime(),old.latestIncludedFactAt(),old.schemaVersion(),old.applicationVersion(),old.keyVersion(),old.immutableUntil(),old.requestSha256(),old.manifestSha256(),old.manifestJson(),old.state(),list));
        }
        @Override public void complete(BackupSet item,long actor,String correlation) { backups.put(item.backupId(),item); }
        @Override public void failBackup(String id,String reason,long actor,String correlation) {
            BackupSet old=backups.get(id); backups.put(id,new BackupSet(old.backupId(),old.environment(),old.tenantIds(),old.tenantScopeSha256(),old.pointInTime(),old.latestIncludedFactAt(),old.schemaVersion(),old.applicationVersion(),old.keyVersion(),old.immutableUntil(),old.requestSha256(),"","","FAILED",old.objects()));
        }
        @Override public Optional<RestoreEvidence> findDrill(String id) { return Optional.ofNullable(drills.get(id)); }
        @Override public void startDrill(RestoreEvidence item,long actor,String correlation) { drills.put(item.drillId(),item); }
        @Override public void finishDrill(RestoreEvidence item,long actor,String correlation) { drills.put(item.drillId(),item); }
    }
    private static final class MemoryStore implements ObjectStore {
        final Map<String,byte[]> bytes=new HashMap<>();
        @Override public void putNew(String key,byte[] content,Instant until) { if(bytes.putIfAbsent(key,content.clone())!=null) throw new ServiceException("duplicate",409); }
        @Override public byte[] get(String key) { byte[] value=bytes.get(key); if(value==null) throw new ServiceException("missing",422); return value.clone(); }
    }
    private static final class SyntheticSource implements Source {
        boolean fail;
        Instant capturedPoint;
        @Override public List<SourceObject> capture(Set<String> tenants,Instant point) {
            if(fail) throw new ServiceException("synthetic interruption",503);
            capturedPoint = point;
            return List.of(
                object(DataClass.MYSQL,"mysql/authoritative.sql","db",tenants),
                object(DataClass.BUSINESS_OBJECT,"objects/inventory.json","objects",tenants),
                object(DataClass.CONFIG,"config/application.yaml","config",tenants),
                object(DataClass.TEMPLATE,"templates/store.json","template",tenants),
                object(DataClass.MIGRATION,"migration/checksums.json","migration",tenants),
                object(DataClass.EVIDENCE,"evidence/index.json","evidence",tenants));
        }
        private static SourceObject object(DataClass type,String name,String content,Set<String> tenants) {
            return new SourceObject(type,name,"application/json",tenants,content.getBytes());
        }
    }
    private static final class SyntheticTarget implements RestoreTarget {
        final Map<String,byte[]> restored=new HashMap<>(); boolean begun; boolean aborted;
        final long validatedFactRows=1_000_000L;
        Reconciliation reconciliation=new Reconciliation(true,true,0,0,0,0);
        @Override public void beginEmpty(String drill,Set<String> tenants) { if(begun||!restored.isEmpty()) throw new ServiceException("not empty",409); begun=true; }
        @Override public void restore(DataClass type,String name,byte[] content) { if(!begun) throw new ServiceException("not begun",409); restored.put(name,content.clone()); }
        @Override public Reconciliation validateAndReconcile(String schema,Instant point) { return reconciliation; }
        @Override public void complete() { begun=false; }
        @Override public void abort() { aborted=true; restored.clear(); begun=false; }
    }
}
