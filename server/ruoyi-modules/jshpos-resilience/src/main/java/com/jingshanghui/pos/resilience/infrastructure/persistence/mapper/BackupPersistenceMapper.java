package com.jingshanghui.pos.resilience.infrastructure.persistence.mapper;

import com.jingshanghui.pos.resilience.infrastructure.persistence.BackupPersistenceParams.*;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 备份目录、对象和演练的 XML_ONLY Mapper；不暴露通用更新或删除。 */
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
