package com.jingshanghui.pos.foundation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ConfigTemplateVersionMapper extends BaseMapper<ConfigTemplateVersionEntity> {

    /**
     * 串行化同一租户、同一模板的版本号分配。可信租户参数是原生 SQL 的第一道边界，
     * MyBatis tenant interceptor 仍提供第二道约束。
     */
    @Select("""
        SELECT *
        FROM jsh_config_template_version
        WHERE tenant_id = #{trustedTenantId}
          AND template_id = #{templateId}
        ORDER BY version_no DESC
        LIMIT 1
        FOR UPDATE
        """)
    ConfigTemplateVersionEntity selectLatestForUpdate(
        @Param("trustedTenantId") String trustedTenantId,
        @Param("templateId") Long templateId
    );
}
