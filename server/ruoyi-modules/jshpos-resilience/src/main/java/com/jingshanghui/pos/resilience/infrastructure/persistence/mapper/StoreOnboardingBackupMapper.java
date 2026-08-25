package com.jingshanghui.pos.resilience.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jingshanghui.pos.resilience.application.port.StoreOnboardingBackupPort.BackupReadiness;
import org.apache.ibatis.annotations.Param;

/**
 * 开店检查专用恢复演练只读投影。
 *
 * <p>恢复目录允许一个备份集合覆盖多个租户，因此目录表没有逐行 tenant_id；
 * 本查询关闭通用列注入，并以应用层可信租户参数在清单范围内显式校验。</p>
 */
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface StoreOnboardingBackupMapper {
    BackupReadiness findLatestPass(@Param("tenantId") String tenantId);
}
