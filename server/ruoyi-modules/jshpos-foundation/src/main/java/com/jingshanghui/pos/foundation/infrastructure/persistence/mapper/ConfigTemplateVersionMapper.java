package com.jingshanghui.pos.foundation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import org.apache.ibatis.annotations.Param;

/** 配置模板版本持久化 Mapper；复杂锁定读取由 XML 显式实现。 */
public interface ConfigTemplateVersionMapper extends BaseMapper<ConfigTemplateVersionEntity> {

    /**
     * 串行化同一租户、同一模板的版本号分配。可信租户参数是原生 SQL 的第一道边界，
     * MyBatis tenant interceptor 仍提供第二道约束。
     */
    ConfigTemplateVersionEntity selectLatestForUpdate(
        @Param("trustedTenantId") String trustedTenantId,
        @Param("templateId") Long templateId
    );
}
