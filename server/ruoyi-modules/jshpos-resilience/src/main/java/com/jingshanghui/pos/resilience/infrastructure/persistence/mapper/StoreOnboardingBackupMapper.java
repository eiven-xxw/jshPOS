package com.jingshanghui.pos.resilience.infrastructure.persistence.mapper;

import com.jingshanghui.pos.resilience.application.port.StoreOnboardingBackupPort.BackupReadiness;
import org.apache.ibatis.annotations.Param;

/** 开店检查专用恢复演练只读投影。 */
public interface StoreOnboardingBackupMapper {
    BackupReadiness findLatestPass(@Param("tenantId") String tenantId);
}
