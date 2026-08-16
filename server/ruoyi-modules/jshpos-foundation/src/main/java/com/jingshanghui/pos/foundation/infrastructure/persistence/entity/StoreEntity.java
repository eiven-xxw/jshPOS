package com.jingshanghui.pos.foundation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("jsh_store")
public class StoreEntity extends TenantEntity {

    @TableId
    private Long storeId;
    private Long orgUnitId;
    private Long platformDeptId;
    private String storeCode;
    private String storeName;
    private String zoneId;
    private LocalTime businessDayStart;
    private String status;
    @Version
    private Integer version;
}
