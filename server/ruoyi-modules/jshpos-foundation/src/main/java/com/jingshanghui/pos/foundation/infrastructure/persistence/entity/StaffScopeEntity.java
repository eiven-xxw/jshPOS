package com.jingshanghui.pos.foundation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("jsh_staff_scope")
public class StaffScopeEntity extends TenantEntity {

    @TableId
    private Long staffScopeId;
    private Long userId;
    private String scopeType;
    private Long orgUnitId;
    private Long storeId;
    private String status;
    @Version
    private Integer version;
}
