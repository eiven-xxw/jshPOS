package com.jingshanghui.pos.resilience.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static com.jingshanghui.pos.resilience.domain.BackupModels.*;
import static org.assertj.core.api.Assertions.*;

/** BAK 状态、范围、路径、时间和守恒规则回归。 */
class BackupRulesTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final Set<String> TENANTS = Set.of("TENANT_A", "TENANT_B");

    @Test void acceptsCompleteSyntheticRecoverySetAndMeasuresTargets() {
        CreateBackup command = validCreate();
        BackupRules.validateCreate(command, NOW);
        List<SourceObject> objects = completeObjects(TENANTS);
        BackupRules.validateSourceObjects(TENANTS, objects);
        String scope = BackupRules.tenantScopeSha256(TENANTS);
        assertThat(scope).hasSize(64).isEqualTo(BackupRules.tenantScopeSha256(Set.of("TENANT_B", "TENANT_A")));
        BackupSet set = set("AVAILABLE", TENANTS, NOW.minusSeconds(300), NOW);
        BackupRules.validateRestore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 9, ulid(3)), set);
        BackupRules.requireSuccessfulReconciliation(new Reconciliation(true, true, 0, 0, 0, 0));
        assertThat(BackupRules.rpoSeconds(set)).isEqualTo(300);
        assertThat(BackupRules.rtoSeconds(NOW, NOW)).isEqualTo(1);
        assertThat(BackupRules.rtoSeconds(NOW, NOW.plusMillis(1001))).isEqualTo(2);
        assertThat(BackupRules.sha256("x".getBytes(StandardCharsets.UTF_8))).hasSize(64);
    }

    @Test void rejectsInvalidCreateBoundaries() {
        CreateBackup valid = validCreate();
        assertInvalid(new CreateBackup(null, valid.environment(), TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup("bad", valid.environment(), TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, "bad"));
        assertInvalid(new CreateBackup(valid.backupId(), "bad value", TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), null, TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), "bad value", valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), "bad value", valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), "bad value", valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), Set.of(), valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, null, valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, valid.pointInTime(), null, valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, valid.pointInTime(), valid.latestIncludedFactAt(), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), null, 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, NOW.plusSeconds(301), NOW, valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, NOW, NOW.plusSeconds(1), valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), valid.immutableUntil(), 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, NOW, NOW, valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), NOW, 9, valid.correlationId()));
        assertInvalid(new CreateBackup(valid.backupId(), valid.environment(), TENANTS, NOW, NOW, valid.schemaVersion(), valid.applicationVersion(), valid.keyVersion(), NOW.plusSeconds(1), 0, valid.correlationId()));
    }

    @Test void rejectsIncompleteDuplicateUnsafeOversizedAndCrossTenantObjects() {
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS,
            Collections.nCopies(10_001, completeObjects(TENANTS).get(0)))).isInstanceOf(ServiceException.class);
        List<SourceObject> withNull = new ArrayList<>(completeObjects(TENANTS));
        withNull.set(0, null);
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, withNull)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, List.of(
            new SourceObject(DataClass.MYSQL, "mysql/db.sql", "application/sql", TENANTS, new byte[]{1}))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("数据类别");
        List<SourceObject> duplicate = new ArrayList<>(completeObjects(TENANTS));
        duplicate.set(1, new SourceObject(DataClass.BUSINESS_OBJECT, "mysql/db.sql", "application/json", TENANTS, new byte[]{1}));
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, duplicate)).isInstanceOf(ServiceException.class).hasMessageContaining("重复");
        List<SourceObject> traversal = new ArrayList<>(completeObjects(TENANTS));
        traversal.set(0, new SourceObject(DataClass.MYSQL, "mysql/../secret", "application/sql", TENANTS, new byte[]{1}));
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, traversal)).isInstanceOf(ServiceException.class).hasMessageContaining("路径");
        List<SourceObject> cross = new ArrayList<>(completeObjects(TENANTS));
        cross.set(0, new SourceObject(DataClass.MYSQL, "mysql/db.sql", "application/sql", Set.of("TENANT_A"), new byte[]{1}));
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, cross)).isInstanceOf(ServiceException.class).hasMessageContaining("租户");
        List<SourceObject> empty = new ArrayList<>(completeObjects(TENANTS));
        empty.set(0, new SourceObject(DataClass.MYSQL, "mysql/db.sql", "application/sql", TENANTS, new byte[0]));
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, empty)).isInstanceOf(ServiceException.class).hasMessageContaining("为空");
        List<SourceObject> nullClass = new ArrayList<>(completeObjects(TENANTS));
        nullClass.set(0, new SourceObject(null, "mysql/db.sql", "application/sql", TENANTS, new byte[]{1}));
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, nullClass)).isInstanceOf(ServiceException.class);
        List<SourceObject> badMedia = new ArrayList<>(completeObjects(TENANTS));
        badMedia.set(0, new SourceObject(DataClass.MYSQL, "mysql/db.sql", "bad media", TENANTS, new byte[]{1}));
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, badMedia)).isInstanceOf(ServiceException.class);
        List<SourceObject> backslash = new ArrayList<>(completeObjects(TENANTS));
        backslash.set(0, new SourceObject(DataClass.MYSQL, "mysql\\db.sql", "application/sql", TENANTS, new byte[]{1}));
        assertThatThrownBy(() -> BackupRules.validateSourceObjects(TENANTS, backslash)).isInstanceOf(ServiceException.class);
    }

    @Test void rejectsRestoreScopeSchemaStateTimelineAndReconciliationDifferences() {
        BackupSet available = set("AVAILABLE", TENANTS, NOW.minusSeconds(1), NOW);
        RestoreBackup valid = new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 9, ulid(3));
        assertThatThrownBy(() -> BackupRules.validateRestore(new RestoreBackup("bad", ulid(1), TENANTS, "39", 9, ulid(3)), available)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateRestore(new RestoreBackup(ulid(2), "bad", TENANTS, "39", 9, ulid(3)), available)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateRestore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 9, "bad"), available)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateRestore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "39", 0, ulid(3)), available)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateRestore(new RestoreBackup(ulid(2), ulid(9), TENANTS, "39", 9, ulid(3)), available)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.validateRestore(new RestoreBackup(ulid(2), ulid(1), Set.of("TENANT_A"), "39", 9, ulid(3)), available)).isInstanceOf(ServiceException.class).hasMessageContaining("跨范围");
        assertThatThrownBy(() -> BackupRules.validateRestore(new RestoreBackup(ulid(2), ulid(1), TENANTS, "38", 9, ulid(3)), available)).isInstanceOf(ServiceException.class).hasMessageContaining("Schema");
        assertThatThrownBy(() -> BackupRules.validateRestore(valid, set("FAILED", TENANTS, NOW.minusSeconds(1), NOW))).isInstanceOf(ServiceException.class).hasMessageContaining("不可恢复");
        BackupSet withoutObjects = new BackupSet(available.backupId(), available.environment(), available.tenantIds(),
            available.tenantScopeSha256(), available.pointInTime(), available.latestIncludedFactAt(),
            available.schemaVersion(), available.applicationVersion(), available.keyVersion(), available.immutableUntil(),
            available.requestSha256(), available.manifestSha256(), available.manifestJson(), "AVAILABLE", List.of());
        assertThatThrownBy(() -> BackupRules.validateRestore(valid, withoutObjects)).isInstanceOf(ServiceException.class).hasMessageContaining("清单");
        assertThatThrownBy(() -> BackupRules.requireSuccessfulReconciliation(new Reconciliation(false, true, 0, 0, 0, 0))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.requireSuccessfulReconciliation(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.requireSuccessfulReconciliation(new Reconciliation(true, false, 0, 0, 0, 0))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.requireSuccessfulReconciliation(new Reconciliation(true, true, 1, 0, 0, 0))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.requireSuccessfulReconciliation(new Reconciliation(true, true, 0, 1, 0, 0))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.requireSuccessfulReconciliation(new Reconciliation(true, true, 0, 0, 1, 0))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.requireSuccessfulReconciliation(new Reconciliation(true, true, 0, 0, 0, 1))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.rpoSeconds(set("AVAILABLE", TENANTS, NOW.plusSeconds(1), NOW))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.rtoSeconds(NOW, NOW.minusSeconds(1))).isInstanceOf(ServiceException.class);
    }

    @Test void rejectsNullOversizedMalformedAndNullElementTenantScopes() {
        assertThatThrownBy(() -> BackupRules.tenantScopeSha256(null)).isInstanceOf(ServiceException.class);
        Set<String> tooMany = new HashSet<>();
        for (int index = 0; index < 1_001; index++) tooMany.add("T" + index);
        assertThatThrownBy(() -> BackupRules.tenantScopeSha256(tooMany)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BackupRules.tenantScopeSha256(Set.of("bad tenant"))).isInstanceOf(ServiceException.class);
        Set<String> withNull = new HashSet<>();
        withNull.add("TENANT_A"); withNull.add(null);
        assertThatThrownBy(() -> BackupRules.tenantScopeSha256(withNull)).isInstanceOf(ServiceException.class);
    }

    @Test void canonicalManifestIsStableAndExcludesSecrets() {
        BackupSet backup = set("AVAILABLE", TENANTS, NOW.minusSeconds(1), NOW);
        String first = new String(CanonicalBackupManifest.encode(backup), StandardCharsets.UTF_8);
        String second = new String(CanonicalBackupManifest.encode(backup), StandardCharsets.UTF_8);
        assertThat(first).isEqualTo(second).contains("\"tenantIds\":[\"TENANT_A\",\"TENANT_B\"]")
            .contains("\"logicalName\":\"mysql/db.sql\"").doesNotContain("plaintext-content", "secretKey");
    }

    private static void assertInvalid(CreateBackup command) {
        assertThatThrownBy(() -> BackupRules.validateCreate(command, NOW)).isInstanceOf(ServiceException.class);
    }
    static CreateBackup validCreate() {
        return new CreateBackup(ulid(1), "synthetic", TENANTS, NOW, NOW.minusSeconds(300), "39", "1.0.0", "synthetic-v1", NOW.plusSeconds(86400), 9, ulid(3));
    }
    static List<SourceObject> completeObjects(Set<String> tenants) {
        return List.of(
            new SourceObject(DataClass.MYSQL,"mysql/db.sql","application/sql",tenants,"db".getBytes()),
            new SourceObject(DataClass.BUSINESS_OBJECT,"objects/list.json","application/json",tenants,"objects".getBytes()),
            new SourceObject(DataClass.CONFIG,"config/app.yaml","text/yaml",tenants,"config".getBytes()),
            new SourceObject(DataClass.TEMPLATE,"templates/store.json","application/json",tenants,"template".getBytes()),
            new SourceObject(DataClass.MIGRATION,"migration/checksums.json","application/json",tenants,"migration".getBytes()),
            new SourceObject(DataClass.EVIDENCE,"evidence/index.json","application/json",tenants,"evidence".getBytes()));
    }
    static BackupSet set(String state, Set<String> tenants, Instant latest, Instant point) {
        String scope = BackupRules.tenantScopeSha256(tenants);
        ObjectDescriptor descriptor = new ObjectDescriptor(ulid(4), DataClass.MYSQL, "mysql/db.sql", "application/sql", scope,
            2,"a".repeat(64),30,"b".repeat(64),"synthetic-v1","A".repeat(16),"backups/"+scope+"/"+ulid(1)+"/"+"b".repeat(64)+".aead");
        return new BackupSet(ulid(1),"synthetic",tenants,scope,point,latest,"39","1.0.0","synthetic-v1",
            point.plusSeconds(86400),"c".repeat(64),"d".repeat(64),"{}",state,List.of(descriptor));
    }
    static String ulid(int last) { return "01K2A00000000000000000000" + last; }
}
