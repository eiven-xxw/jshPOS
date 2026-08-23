package com.jingshanghui.pos.subscription.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订阅当前投影持久化实体；只能由具名状态/期限命令更新，不开放通用删除。
 */
@Data
@TableName("sub_subscription")
public class SubscriptionEntity {
    /** 订阅 ULID。 */ @TableId private String subscriptionId;
    /** 可信 SaaS 租户号。 */ private String tenantId;
    /** SaaS 套餐平台主键。 */ private Long planId;
    /** 冻结的 SaaS 权益版本 ULID。 */ private String entitlementVersionId;
    /** 不透明合同引用，不保存合同原文。 */ private String contractRef;
    /** 不透明外部订单引用，不表达资金成功。 */ private String externalOrderRef;
    /** 订阅具名当前状态。 */ private String state;
    /** 状态乐观锁版本。 */ private Integer stateVersion;
    /** 当前期限版本号。 */ private Integer currentTermVersion;
    /** 当前期限开始时间 UTC。 */ private LocalDateTime startsAt;
    /** 当前期限结束时间 UTC。 */ private LocalDateTime endsAt;
    /** 当前宽限结束时间 UTC。 */ private LocalDateTime graceEndsAt;
    /** IANA 业务时区。 */ private String businessTimeZone;
    /** 受控降级策略版本。 */ private String degradationPolicyVersion;
    /** 当前投影内容 SHA-256。 */ private String contentSha256;
    /** 创建时间 UTC。 */ private LocalDateTime createdAt;
    /** 最近变更时间 UTC。 */ private LocalDateTime updatedAt;
}
