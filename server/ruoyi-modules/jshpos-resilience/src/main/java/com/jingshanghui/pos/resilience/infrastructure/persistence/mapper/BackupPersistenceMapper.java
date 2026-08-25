package com.jingshanghui.pos.resilience.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jingshanghui.pos.resilience.infrastructure.persistence.BackupPersistenceParams.*;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 备份目录、对象和演练的 XML_ONLY Mapper；不暴露通用更新或删除。
 *
 * <p>备份集合可覆盖多个租户，已发布表以服务端生成的 tenant_ids_csv 与范围摘要表达主权，
 * 不存在逐行 tenant_id。这里必须关闭通用列注入；可信范围由 AuthorizedScope、请求摘要和
 * BackupRecoveryService 的集合等值校验共同约束，禁止客户端 tenant 参与授权。</p>
 */
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface BackupPersistenceMapper {
    BackupRow selectBackup(@Param("backupId") String backupId);
    List<ObjectRow> selectObjects(@Param("backupId") String backupId);
    int insertBackup(InsertBackup command);
    int insertObject(InsertObject command);
    int transitionBackup(TransitionBackup command);
    DrillRow selectDrill(@Param("drillId") String drillId);
    List<CheckRow> selectChecks(@Param("drillId") String drillId);
    int insertDrill(InsertDrill command);
    int finishDrill(FinishDrill command);
    int insertCheck(InsertCheck command);
    int insertAudit(InsertAudit command);
}
