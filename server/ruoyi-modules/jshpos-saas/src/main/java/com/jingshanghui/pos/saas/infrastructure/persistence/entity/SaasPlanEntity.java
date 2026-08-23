package com.jingshanghui.pos.saas.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SaaS 套餐主数据持久化实体；简单单表读写使用 MyBatis-Plus，权益发布流程使用 XML 受控方法。
 */
@Data
@TableName("saas_plan")
public class SaasPlanEntity {
    /** 平台 BIGINT 主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long planId;
    /** 租户无关且全局唯一的套餐代码。 */
    private String planCode;
    /** 套餐展示名称。 */
    private String planName;
    /** RuoYi 技术菜单套餐引用，不承载商业权益。 */
    private Long platformPackageId;
    /** 技术租户账号上限。 */
    private Long accountLimit;
    /** ACTIVE 或 RETIRED。 */
    private String status;
    /** 创建时间，UTC。 */
    private LocalDateTime createdAt;
    /** 最近更新时间，UTC。 */
    private LocalDateTime updatedAt;
}
