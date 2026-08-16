package com.jingshanghui.pos.foundation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalTime;

public interface StoreMapper extends BaseMapper<StoreEntity> {

    /**
     * 原生 SQL 必须显式使用由应用服务传入的可信租户值；租户拦截器会形成第二重约束。
     */
    @Select("""
        SELECT business_day_start
        FROM jsh_store
        WHERE tenant_id = #{trustedTenantId}
          AND store_code = #{storeCode}
          AND status <> 'INACTIVE'
        LIMIT 1
        """)
    LocalTime selectBusinessDayStartByCode(
        @Param("trustedTenantId") String trustedTenantId,
        @Param("storeCode") String storeCode
    );
}
