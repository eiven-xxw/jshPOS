package com.jingshanghui.pos.release.infrastructure.persistence.mapper;

import com.jingshanghui.pos.release.infrastructure.persistence.ReleasePersistenceParams.*;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 发布、灰度和终端任务的 XML Mapper；不暴露任意更新、跨租户查询或删除。 */
public interface ReleasePersistenceMapper {
    ReleaseRow selectRelease(@Param("tenantId") String tenantId, @Param("releaseId") String releaseId);
    RolloutRow selectRollout(@Param("tenantId") String tenantId, @Param("rolloutId") String rolloutId);
    TaskRow selectTask(@Param("tenantId") String tenantId, @Param("taskId") String taskId);
    List<Long> selectScopes(@Param("tenantId") String tenantId, @Param("aggregateType") String aggregateType,
                            @Param("aggregateId") String aggregateId);
    CommandRow selectCommand(@Param("tenantId") String tenantId, @Param("commandType") String commandType,
                             @Param("idempotencyKey") String idempotencyKey);
    int insertRelease(InsertRelease command);
    int insertScope(InsertScope command);
    int transitionRelease(TransitionRelease command);
    int insertRollout(InsertRollout command);
    int transitionRollout(TransitionRollout command);
    int insertTask(InsertTask command);
    int transitionTask(TransitionTask command);
    SummaryRow summarizeTasks(@Param("tenantId") String tenantId, @Param("rolloutId") String rolloutId);
    int insertCommand(InsertCommand command);
    int insertEvent(InsertEvent command);
    int insertAudit(InsertAudit command);
}
