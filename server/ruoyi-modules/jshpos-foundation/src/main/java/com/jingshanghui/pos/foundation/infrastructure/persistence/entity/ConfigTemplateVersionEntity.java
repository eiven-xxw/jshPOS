package com.jingshanghui.pos.foundation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("jsh_config_template_version")
public class ConfigTemplateVersionEntity extends TenantEntity {

    @TableId
    private Long configVersionId;
    private Long templateId;
    private Integer versionNo;
    private String schemaVersion;
    private String state;
    private String contentJson;
    private String contentSha256;
    private Long publishedBy;
    private LocalDateTime publishedAt;
}
