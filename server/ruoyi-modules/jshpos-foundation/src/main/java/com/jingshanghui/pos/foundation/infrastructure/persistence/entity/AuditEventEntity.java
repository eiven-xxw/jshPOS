package com.jingshanghui.pos.foundation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("jsh_audit_event")
public class AuditEventEntity extends TenantEntity {

    @TableId
    private Long auditId;
    private Long actorUserId;
    private String actorName;
    private String correlationId;
    private String actionCode;
    private String targetType;
    private String targetId;
    private String result;
    private String beforeSha256;
    private String afterSha256;
    private String summaryJson;
    private LocalDateTime occurredAt;
}
