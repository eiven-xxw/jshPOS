package com.jingshanghui.pos.promotion.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

/** 简单促销规则身份持久化实体；版本与事实不使用 MyBatis-Plus 实体。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prm_rule")
public class PromotionRuleEntity extends TenantEntity {
    /** 规则ULID。 */
    @TableId
    private String ruleId;
    /** 租户内规则编码。 */
    private String ruleCode;
    /** 规则名称。 */
    private String ruleName;
    /** 规则身份状态。 */
    private String status;
    /** 乐观锁版本。 */
    @Version
    private Integer version;
    /** 创建人。 */
    private Long createdBy;
    /** 创建时间UTC。 */
    private LocalDateTime createdAt;
    /** 更新时间UTC。 */
    private LocalDateTime updatedAt;
}
