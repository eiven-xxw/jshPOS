package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskItemView;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LatestOpenItem;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTaskItemEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 价签任务项快照和受控状态迁移 Mapper。 */
public interface ShelfLabelTaskItemMapper extends BaseMapper<ShelfLabelTaskItemEntity> {

    LatestOpenItem findLatestOpenItem(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                      @Param("skuId") Long skuId, @Param("unitId") Long unitId);

    List<Long> findOpenTaskIds(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                               @Param("skuId") Long skuId, @Param("unitId") Long unitId);

    int supersedeOpenItems(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                           @Param("skuId") Long skuId, @Param("unitId") Long unitId,
                           @Param("replacingItemId") Long replacingItemId,
                           @Param("effectiveAt") LocalDateTime effectiveAt,
                           @Param("scopePriority") int scopePriority,
                           @Param("sourcePriceVersion") int sourcePriceVersion,
                           @Param("sourcePriceBookId") Long sourcePriceBookId,
                           @Param("updatedAt") LocalDateTime updatedAt);

    List<TaskItemView> listTaskItems(@Param("tenantId") String tenantId, @Param("taskId") Long taskId);

    TaskItemView findTaskItem(@Param("tenantId") String tenantId, @Param("itemId") Long itemId);

    int transition(@Param("tenantId") String tenantId, @Param("itemId") Long itemId,
                   @Param("expectedState") String expectedState, @Param("version") int version,
                   @Param("targetState") String targetState, @Param("exceptionReason") String exceptionReason,
                   @Param("updatedAt") LocalDateTime updatedAt);
}
