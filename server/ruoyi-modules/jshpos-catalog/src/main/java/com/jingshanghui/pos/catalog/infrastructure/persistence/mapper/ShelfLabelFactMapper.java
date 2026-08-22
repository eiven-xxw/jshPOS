package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LabelEvent;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LabelException;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredCommand;
import org.apache.ibatis.annotations.Param;

/** 价签只追加事件和异常 Mapper；SQL 仅由 XML 提供。 */
public interface ShelfLabelFactMapper {

    StoredCommand findCommand(@Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

    int insertEvent(LabelEvent event);

    int insertException(LabelException exception);
}
