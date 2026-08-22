package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TemplateView;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredTemplateCommand;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTemplateEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 价签模板简单 CRUD 使用 MyBatis-Plus，作用域优先级查询进入 XML。 */
public interface ShelfLabelTemplateMapper extends BaseMapper<ShelfLabelTemplateEntity> {

    StoredTemplateCommand findTemplateCommand(@Param("tenantId") String tenantId,
                                               @Param("idempotencyKey") String idempotencyKey);

    TemplateView findPublishedTemplate(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);

    List<TemplateView> listTemplates(@Param("tenantId") String tenantId,
                                     @Param("storeIds") List<Long> storeIds,
                                     @Param("state") String state,
                                     @Param("limit") int limit);
}
