package com.jingshanghui.pos.service.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.service.application.model.ServiceModels.*;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort.*;
import com.jingshanghui.pos.service.infrastructure.persistence.entity.ServiceTicketEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Service Owner HYBRID Mapper：简单工单插入用 MP，锁、复杂查询和受控更新用 XML。 */
public interface ServicePersistenceMapper extends BaseMapper<ServiceTicketEntity> {
    void insertCatalog(CatalogWrite value); void insertCatalogItem(CatalogItemWrite value);
    CatalogRecord findCatalog(@Param("tenantId") String tenantId, @Param("catalogId") String catalogId);
    CatalogRecord lockCatalog(@Param("tenantId") String tenantId, @Param("catalogId") String catalogId);
    List<CatalogItemRecord> listCatalogItems(@Param("tenantId") String tenantId, @Param("catalogId") String catalogId);
    int publishCatalog(CatalogPublish value);
    void insertProject(ProjectWrite value); void insertProjectCheck(ProjectCheckWrite value);
    ProjectRecord findProject(@Param("tenantId") String tenantId, @Param("projectId") String projectId);
    ProjectRecord lockProject(@Param("tenantId") String tenantId, @Param("projectId") String projectId);
    List<ProjectRecord> listProjects(@Param("tenantId") String tenantId, @Param("storeId") Long storeId, @Param("limit") int limit);
    List<CheckRecord> listProjectChecks(@Param("tenantId") String tenantId, @Param("projectId") String projectId);
    int changeProjectState(ProjectStateChange value); int completeProjectCheck(ProjectCheckComplete value);
    int countMandatoryIncomplete(@Param("tenantId") String tenantId, @Param("projectId") String projectId);
    TicketRecord findTicket(@Param("tenantId") String tenantId, @Param("ticketId") String ticketId);
    TicketRecord lockTicket(@Param("tenantId") String tenantId, @Param("ticketId") String ticketId);
    List<TicketRecord> listTickets(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                   @Param("state") String state, @Param("limit") int limit);
    int changeTicket(TicketChange value); void appendHistory(HistoryWrite value);
    void insertAttachment(AttachmentWrite value);
    AttachmentStoredRecord findAttachment(@Param("tenantId") String tenantId, @Param("ticketId") String ticketId,
                                           @Param("attachmentId") String attachmentId);
    List<AttachmentRecord> listAttachments(@Param("tenantId") String tenantId, @Param("ticketId") String ticketId);
    int countAttachments(@Param("tenantId") String tenantId, @Param("ticketId") String ticketId);
    int changeAttachmentState(AttachmentStateChange value);
    CommandRecord findCommand(@Param("tenantId") String tenantId, @Param("operation") String operation,
                              @Param("idempotencyKey") String idempotencyKey);
    void appendCommand(CommandWrite value); void appendAudit(AuditWrite value); void appendOutbox(OutboxWrite value);
}
