package com.jingshanghui.pos.resilience.application.port;

import com.jingshanghui.pos.resilience.domain.BackupModels.*;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** BAK Owner 的持久化、对象、密钥、来源和隔离恢复端口。 */
public final class BackupPorts {
    private BackupPorts() {
    }

    public interface Catalog {
        Optional<BackupSet> findBackup(String backupId);
        void reserve(BackupSet backup, long actorId, String correlationId);
        void appendObject(String backupId, ObjectDescriptor object);
        void complete(BackupSet backup, long actorId, String correlationId);
        void failBackup(String backupId, String reasonSha256, long actorId, String correlationId);
        Optional<RestoreEvidence> findDrill(String drillId);
        void startDrill(RestoreEvidence evidence, long actorId, String correlationId);
        void finishDrill(RestoreEvidence evidence, long actorId, String correlationId);
    }

    public interface ObjectStore {
        void putNew(String objectKey, byte[] ciphertext, Instant immutableUntil);
        byte[] get(String objectKey);
    }

    public interface KeyProvider { SecretKey resolve(String keyVersion); }

    public interface Source {
        List<SourceObject> capture(Set<String> tenantIds, Instant pointInTime);
    }

    /** 由平台SRE授权适配器提供的可信租户范围；HTTP请求体不得自行构造该范围。 */
    public interface AuthorizedScope { Set<String> tenantIds(); }

    public interface RestoreTarget {
        void beginEmpty(String drillId, Set<String> tenantIds);
        void restore(DataClass dataClass, String logicalName, byte[] plaintext);
        Reconciliation validateAndReconcile(String schemaVersion, Instant pointInTime);
        void complete();
        void abort();
    }
}
