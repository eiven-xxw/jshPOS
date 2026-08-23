package com.jingshanghui.pos.service.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort.TicketWrite;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务工单持久化实体。仅用于 MyBatis-Plus 简单插入；状态、租约和责任变化必须走 XML 具名条件更新。
 */
@Data
@TableName("svc_work_order")
public class ServiceTicketEntity {
    /** ULID 工单主键。 */
    @TableId private String ticketId;
    /** 可信上下文租户。 */
    private String tenantId;
    /** 数据范围门店。 */
    private Long storeId;
    /** 可空实施项目引用。 */
    private String projectId;
    /** 服务目录定义的服务类型。 */
    private String serviceType;
    /** P0—P3 内部优先级。 */
    private String priority;
    /** 工单主题，不允许真实敏感数据。 */
    private String subject;
    /** 工单说明，不允许真实敏感数据。 */
    private String description;
    /** 具名工单状态。 */
    private String state;
    /** 当前责任人。 */
    private Long assigneeUserId;
    /** 当前认领租约截止 UTC。 */
    private LocalDateTime leaseUntil;
    /** 解决人，用于职责分离。 */
    private Long resolvedBy;
    /** 独立关闭复核人。 */
    private Long closedBy;
    /** 解决时冻结的摘要。 */
    private String resolutionSummary;
    /** 内部响应目标 UTC，不是商业 SLA。 */
    private LocalDateTime targetAt;
    /** 乐观锁版本。 */
    private Integer recordVersion;
    /** 当前投影内容 SHA-256。 */
    private String contentSha256;
    /** 创建人。 */
    private Long creatorUserId;
    /** 创建 UTC。 */
    private LocalDateTime createdAt;
    /** 最后受控更新时间 UTC。 */
    private LocalDateTime updatedAt;

    public static ServiceTicketEntity from(TicketWrite value) {
        ServiceTicketEntity entity = new ServiceTicketEntity();
        entity.setTicketId(value.ticketId()); entity.setTenantId(value.tenantId()); entity.setStoreId(value.storeId());
        entity.setProjectId(value.projectId()); entity.setServiceType(value.serviceType()); entity.setPriority(value.priority());
        entity.setSubject(value.subject()); entity.setDescription(value.description()); entity.setState(value.state());
        entity.setTargetAt(value.targetAt()); entity.setRecordVersion(0); entity.setContentSha256(value.contentSha256());
        entity.setCreatorUserId(value.creatorUserId()); entity.setCreatedAt(value.createdAt()); entity.setUpdatedAt(value.createdAt());
        return entity;
    }
}
