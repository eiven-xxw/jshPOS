package com.jingshanghui.pos.resilience.domain;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.TreeSet;

import static com.jingshanghui.pos.resilience.domain.BackupModels.*;

/** 生成字段顺序固定、字符串已受规则白名单约束的规范 JSON 清单。 */
public final class CanonicalBackupManifest {
    private CanonicalBackupManifest() {
    }

    public static byte[] encode(BackupSet backup) {
        StringBuilder json = new StringBuilder(2048).append('{')
            .append("\"backupId\":\"").append(backup.backupId()).append("\",")
            .append("\"environment\":\"").append(backup.environment()).append("\",")
            .append("\"tenantIds\":[");
        boolean first = true;
        for (String tenant : new TreeSet<>(backup.tenantIds())) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(tenant).append('"');
        }
        json.append("],\"tenantScopeSha256\":\"").append(backup.tenantScopeSha256()).append("\",")
            .append("\"pointInTime\":\"").append(backup.pointInTime()).append("\",")
            .append("\"schemaVersion\":\"").append(backup.schemaVersion()).append("\",")
            .append("\"applicationVersion\":\"").append(backup.applicationVersion()).append("\",")
            .append("\"keyVersion\":\"").append(backup.keyVersion()).append("\",")
            .append("\"immutableUntil\":\"").append(backup.immutableUntil()).append("\",\"objects\":[");
        first = true;
        for (ObjectDescriptor object : backup.objects().stream()
            .sorted(Comparator.comparing(ObjectDescriptor::logicalName)).toList()) {
            if (!first) json.append(',');
            first = false;
            json.append('{').append("\"dataClass\":\"").append(object.dataClass()).append("\",")
                .append("\"logicalName\":\"").append(object.logicalName()).append("\",")
                .append("\"mediaType\":\"").append(object.mediaType()).append("\",")
                .append("\"plaintextSizeBytes\":").append(object.plaintextSizeBytes()).append(',')
                .append("\"plaintextSha256\":\"").append(object.plaintextSha256()).append("\",")
                .append("\"ciphertextSizeBytes\":").append(object.ciphertextSizeBytes()).append(',')
                .append("\"ciphertextSha256\":\"").append(object.ciphertextSha256()).append("\",")
                .append("\"keyVersion\":\"").append(object.keyVersion()).append("\",")
                .append("\"nonceBase64\":\"").append(object.nonceBase64()).append("\",")
                .append("\"objectKey\":\"").append(object.objectKey()).append("\"}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }
}
