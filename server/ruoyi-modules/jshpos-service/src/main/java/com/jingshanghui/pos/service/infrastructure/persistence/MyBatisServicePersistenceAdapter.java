package com.jingshanghui.pos.service.infrastructure.persistence;

import com.jingshanghui.pos.service.application.model.ServiceModels.*;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort.*;
import com.jingshanghui.pos.service.infrastructure.persistence.entity.ServiceTicketEntity;
import com.jingshanghui.pos.service.infrastructure.persistence.mapper.ServicePersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Service 持久化适配器；不向应用层暴露 Mapper 或通用更新能力。 */
@Repository
@RequiredArgsConstructor
public class MyBatisServicePersistenceAdapter implements ServicePersistencePort {
    private final ServicePersistenceMapper mapper;

    @Override
    public void insertCatalog(CatalogWrite value) {
        mapper.insertCatalog(value);
    }

    @Override
    public void insertCatalogItem(CatalogItemWrite value) {
        mapper.insertCatalogItem(value);
    }

    @Override
    public CatalogRecord findCatalog(String tenantId, String catalogId) {
        return mapper.findCatalog(tenantId, catalogId);
    }

    @Override
    public CatalogRecord lockCatalog(String tenantId, String catalogId) {
        return mapper.lockCatalog(tenantId, catalogId);
    }

    @Override
    public List<CatalogItemRecord> listCatalogItems(String tenantId, String catalogId) {
        return mapper.listCatalogItems(tenantId, catalogId);
    }

    @Override
    public int publishCatalog(CatalogPublish value) {
        return mapper.publishCatalog(value);
    }

    @Override
    public void insertProject(ProjectWrite value) {
        mapper.insertProject(value);
    }

    @Override
    public void insertProjectCheck(ProjectCheckWrite value) {
        mapper.insertProjectCheck(value);
    }

    @Override
    public ProjectRecord findProject(String tenantId, String projectId) {
        return mapper.findProject(tenantId, projectId);
    }

    @Override
    public ProjectRecord lockProject(String tenantId, String projectId) {
        return mapper.lockProject(tenantId, projectId);
    }

    @Override
    public List<ProjectRecord> listProjects(String tenantId, Long storeId, int limit) {
        return mapper.listProjects(tenantId, storeId, limit);
    }

    @Override
    public List<CheckRecord> listProjectChecks(String tenantId, String projectId) {
        return mapper.listProjectChecks(tenantId, projectId);
    }

    @Override
    public int changeProjectState(ProjectStateChange value) {
        return mapper.changeProjectState(value);
    }

    @Override
    public int completeProjectCheck(ProjectCheckComplete value) {
        return mapper.completeProjectCheck(value);
    }

    @Override
    public int countMandatoryIncomplete(String tenantId, String projectId) {
        return mapper.countMandatoryIncomplete(tenantId, projectId);
    }

    @Override
    public void insertTicket(TicketWrite value) {
        mapper.insert(ServiceTicketEntity.from(value));
    }

    @Override
    public TicketRecord findTicket(String tenantId, String ticketId) {
        return mapper.findTicket(tenantId, ticketId);
    }

    @Override
    public TicketRecord lockTicket(String tenantId, String ticketId) {
        return mapper.lockTicket(tenantId, ticketId);
    }

    @Override
    public List<TicketRecord> listTickets(String tenantId, Long storeId, String state, int limit) {
        return mapper.listTickets(tenantId, storeId, state, limit);
    }

    @Override
    public int changeTicket(TicketChange value) {
        return mapper.changeTicket(value);
    }

    @Override
    public void appendHistory(HistoryWrite value) {
        mapper.appendHistory(value);
    }

    @Override
    public void insertAttachment(AttachmentWrite value) {
        mapper.insertAttachment(value);
    }

    @Override
    public AttachmentStoredRecord findAttachment(String tenantId, String ticketId, String attachmentId) {
        return mapper.findAttachment(tenantId, ticketId, attachmentId);
    }

    @Override
    public List<AttachmentRecord> listAttachments(String tenantId, String ticketId) {
        return mapper.listAttachments(tenantId, ticketId);
    }

    @Override
    public int countAttachments(String tenantId, String ticketId) {
        return mapper.countAttachments(tenantId, ticketId);
    }

    @Override
    public int changeAttachmentState(AttachmentStateChange value) {
        return mapper.changeAttachmentState(value);
    }

    @Override
    public CommandRecord findCommand(String tenantId, String operation, String idempotencyKey) {
        return mapper.findCommand(tenantId, operation, idempotencyKey);
    }

    @Override
    public void appendCommand(CommandWrite value) {
        mapper.appendCommand(value);
    }

    @Override
    public void appendAudit(AuditWrite value) {
        mapper.appendAudit(value);
    }

    @Override
    public void appendOutbox(OutboxWrite value) {
        mapper.appendOutbox(value);
    }
}
