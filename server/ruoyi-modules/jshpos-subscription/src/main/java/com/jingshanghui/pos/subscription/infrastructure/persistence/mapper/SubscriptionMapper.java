package com.jingshanghui.pos.subscription.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.*;
import com.jingshanghui.pos.subscription.application.port.SubscriptionPersistencePort.*;
import com.jingshanghui.pos.subscription.infrastructure.persistence.entity.SubscriptionEntity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订阅持久化 Mapper：BaseMapper 仅供简单读，状态锁、追加事实和调度租约进入 XML。
 * 平台跨租户操作由应用层强制平台管理员授权，租户自读由可信上下文交叉校验。
 */
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface SubscriptionMapper extends BaseMapper<SubscriptionEntity> {
    SubscriptionRecord find(@Param("subscriptionId") String subscriptionId);
    SubscriptionRecord lock(@Param("subscriptionId") String subscriptionId);
    SubscriptionRecord findByTenant(@Param("tenantId") String tenantId);
    List<TermRecord> listTerms(@Param("subscriptionId") String subscriptionId);
    List<SubscriptionRecord> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);
    void insertSubscription(SubscriptionWrite write);
    int changeState(StateChange change);
    int changeCurrentTerm(TermProjectionChange change);
    void appendTerm(TermWrite write);
    void appendState(StateEventWrite write);
    void appendNotification(NotificationWrite write);
    CommandRecord findCommand(@Param("scope") String scope, @Param("operation") String operation,
        @Param("idempotencyKey") String idempotencyKey);
    void insertCommand(CommandWrite write);
    void appendAudit(AuditWrite write);
    void appendOutbox(OutboxWrite write);
    void ensureCheckpoint(@Param("jobCode") String jobCode, @Param("at") LocalDateTime at);
    int acquireCheckpoint(LeaseWrite write);
    int completeCheckpoint(CheckpointComplete write);
}
