package com.jingshanghui.pos.foundation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("jsh_config_template")
public class ConfigTemplateEntity extends TenantEntity {

    @TableId
    private Long templateId;
    private String templateCode;
    private String templateName;
    private String industry;
    private String status;
    @Version
    private Integer version;
}
