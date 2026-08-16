package com.jingshanghui.pos.foundation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("jsh_config_binding")
public class ConfigBindingEntity extends TenantEntity {

    @TableId
    private Long bindingId;
    private Long templateId;
    private String targetType;
    private Long targetId;
    private Long currentVersionId;
    private Long previousVersionId;
    private LocalDateTime activatedAt;
    @Version
    private Integer version;
}
