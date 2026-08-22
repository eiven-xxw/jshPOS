package com.jingshanghui.pos.resilience.application.port;

import java.time.Instant;

/** Resilience Owner 为开店检查提供最近一次包含当前租户的通过演练事实。 */
public interface StoreOnboardingBackupPort {
    BackupReadiness readiness();

    record BackupReadiness(String drillId, String backupId, Instant endedAt,
                           long rpoSeconds, long rtoSeconds, String evidenceSha256) {
    }
}
