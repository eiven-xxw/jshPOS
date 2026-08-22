package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskView;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredTask;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTaskEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 价签任务具名状态迁移与聚合列表 Mapper。 */
public interface ShelfLabelTaskMapper extends BaseMapper<ShelfLabelTaskEntity> {

    StoredTask findBySource(@Param("tenantId") String tenantId, @Param("sourceEventKey") String sourceEventKey);

    List<TaskView> listTasks(@Param("tenantId") String tenantId, @Param("storeIds") List<Long> storeIds,
                             @Param("state") String state, @Param("limit") int limit);

    TaskView findTaskView(@Param("tenantId") String tenantId, @Param("taskId") Long taskId);

    int markDispatchBlocked(@Param("tenantId") String tenantId, @Param("taskId") Long taskId,
                            @Param("version") int version, @Param("updatedAt") LocalDateTime updatedAt);

    int refreshProjection(@Param("tenantId") String tenantId, @Param("taskId") Long taskId,
                          @Param("updatedAt") LocalDateTime updatedAt);
}
