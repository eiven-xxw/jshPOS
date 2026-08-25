package com.jingshanghui.pos.catalog.infrastructure.persistence;

import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTaskEntity;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTaskItemEntity;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTemplateEntity;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证价签实体只映射 Owner 已发布列，禁止隐式带入平台审计字段。 */
class ShelfLabelEntityPersistenceBoundaryTest {

    @Test
    void ownerEntitiesDeclareTenantIdentityWithoutInheritingPlatformAuditColumns() throws Exception {
        for (Class<?> type : new Class<?>[]{
            ShelfLabelTemplateEntity.class, ShelfLabelTaskEntity.class, ShelfLabelTaskItemEntity.class,
        }) {
            assertThat(BaseEntity.class.isAssignableFrom(type))
                .as("%s must not inherit create_dept/create_by/update_by columns", type.getSimpleName())
                .isFalse();
            assertThat(type.getDeclaredField("tenantId").getType()).isEqualTo(String.class);
        }
    }
}
