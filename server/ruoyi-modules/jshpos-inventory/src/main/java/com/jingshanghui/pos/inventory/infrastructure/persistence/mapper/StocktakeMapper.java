package com.jingshanghui.pos.inventory.infrastructure.persistence.mapper;

import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Head;
import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Line;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.AdjustmentWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.CountWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.HeadStatusUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.HeadWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.LineCountUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.LineCutoffUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.LineWrite;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 盘点锁、不可变计数事实和差异调整持久化边界。 */
public interface StocktakeMapper {

    int insertHead(HeadWrite write);

    int insertLine(LineWrite write);

    Head lockHead(@Param("tenantId") String tenantId, @Param("stocktakeId") String stocktakeId);

    Head findHead(@Param("tenantId") String tenantId, @Param("stocktakeId") String stocktakeId);

    Line lockLine(@Param("tenantId") String tenantId, @Param("stocktakeId") String stocktakeId,
                  @Param("lineId") String lineId);

    List<Line> findLines(@Param("tenantId") String tenantId, @Param("stocktakeId") String stocktakeId);

    int insertCount(CountWrite write);

    int updateLineCount(LineCountUpdate update);

    int updateLineCutoff(LineCutoffUpdate update);

    int updateHeadStatus(HeadStatusUpdate update);

    int insertAdjustment(AdjustmentWrite write);

    int countUncounted(@Param("tenantId") String tenantId, @Param("stocktakeId") String stocktakeId);

    int countByUser(@Param("tenantId") String tenantId, @Param("stocktakeId") String stocktakeId,
                    @Param("userId") Long userId);
}
