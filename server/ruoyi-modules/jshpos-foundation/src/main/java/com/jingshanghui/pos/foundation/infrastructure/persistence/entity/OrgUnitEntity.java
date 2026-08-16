package com.jingshanghui.pos.foundation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("jsh_org_unit")
public class OrgUnitEntity extends TenantEntity {

    @TableId
    private Long orgUnitId;
    private Long parentId;
    private String unitCode;
    private String unitName;
    private String unitType;
    private String status;
    private Integer treeDepth;
    @Version
    private Integer version;
}
