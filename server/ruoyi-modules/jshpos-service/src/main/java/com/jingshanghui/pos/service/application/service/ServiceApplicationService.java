package com.jingshanghui.pos.service.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.service.application.model.ServiceModels.*;
import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort;
import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort.StagedAttachment;
import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort.StoreObject;
import com.jingshanghui.pos.service.application.port.ServiceEntitlementReadPort;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort.*;
import com.jingshanghui.pos.service.domain.ServiceIdGenerator;
import com.jingshanghui.pos.service.domain.ServiceRules;
import com.jingshanghui.pos.service.domain.ServiceStates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * T2-SVC-001 正式应用编排。
 *
 * <p>每个事务只写 Service Owner 自有事实；SaaS/Subscription 只通过服务端权益投影读取。
 * 状态、责任、审计和 Outbox 同事务提交，同键异内容与并发漂移一律失败关闭。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ServiceApplicationService {
    private static final String FEATURE = "SERVICE_OPERATIONS";
    private static final Set<String> INDUSTRIES = Set.of("CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET");
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final ServiceEntitlementReadPort entitlements;
    private final ServicePersistencePort persistence;
    private final ServiceAttachmentStoragePort storage;
    private final ServiceIdGenerator ids;
    private final Clock clock;

    /** 创建目录草稿并冻结检查项；简单主数据写入不承载状态机。 */
    @Transactional
    public CatalogDetail createCatalog(CreateCatalog command) {
        TrustedPrincipal actor = requireTenantAdmin(); String tenant = actor.tenantId();
        String key = ServiceRules.idempotencyKey(command.idempotencyKey()); String correlation = correlation(command.correlationId());
        if (command.versionNo() == null || command.versionNo() < 1) throw bad("SVC-CAT-001", "目录版本非法");
        String industry = ServiceRules.code(command.industryTemplate(), "industryTemplate");
        if (!INDUSTRIES.contains(industry)) throw bad("SVC-CAT-002", "行业模板非法");
        if (command.items() == null || command.items().isEmpty() || command.items().size() > 100) throw bad("SVC-CAT-003", "目录检查项数量非法");
        List<CatalogItemInput> items = normalizeItems(command.items());
        CanonicalJson.Result payload = canonical(Map.of("catalogCode", ServiceRules.code(command.catalogCode(), "catalogCode"),
            "versionNo", command.versionNo(), "industryTemplate", industry, "name", ServiceRules.text(command.name(), "name", 120),
            "items", items.stream().map(this::itemPayload).toList()));
        CommandRecord replay = replay(tenant, "CREATE_CATALOG", key, payload.sha256());
        if (replay != null) return catalogDetail(tenant, replay.resultId());
        LocalDateTime at = now(); String id = ids.next();
        persistence.insertCatalog(new CatalogWrite(id, tenant, ServiceRules.code(command.catalogCode(), "catalogCode"),
            command.versionNo(), industry, ServiceRules.text(command.name(), "name", 120), payload.sha256(), actor.userId(), at));
        for (CatalogItemInput item : items) persistence.insertCatalogItem(new CatalogItemWrite(ids.next(), tenant, id,
            item.itemCode(), item.itemName(), item.mandatory(), item.sequenceNo(), at));
        appendEvidence(tenant, null, "CATALOG", id, 1, "CREATE_CATALOG", null, "DRAFT", payload, correlation, actor.userId(), at);
        record(tenant, "CREATE_CATALOG", key, payload.sha256(), "CATALOG", id, "DRAFT", at);
        return catalogDetail(tenant, id);
    }

    /** 目录发布后内容不可变，只允许 DRAFT 的乐观锁发布。 */
    @Transactional
    public CatalogDetail publishCatalog(CatalogCommand command) {
        TrustedPrincipal actor = requireTenantAdmin(); String tenant = actor.tenantId();
        String key = ServiceRules.idempotencyKey(command.idempotencyKey()); String correlation = correlation(command.correlationId());
        CanonicalJson.Result payload = canonical(Map.of("catalogId", command.catalogId(), "action", "PUBLISH"));
        CommandRecord replay = replay(tenant, "PUBLISH_CATALOG", key, payload.sha256());
        if (replay != null) return catalogDetail(tenant, replay.resultId());
        CatalogRecord current = requireCatalog(tenant, command.catalogId(), true);
        if (!"DRAFT".equals(current.state())) throw conflict("SVC-STATE-001", "只有草稿目录可发布");
        LocalDateTime at = now();
        if (persistence.publishCatalog(new CatalogPublish(tenant, current.catalogId(), current.recordVersion(), actor.userId(), at)) != 1)
            throw conflict("SVC-CONC-001", "目录发布并发冲突");
        appendEvidence(tenant, null, "CATALOG", current.catalogId(), current.recordVersion() + 1, "PUBLISH_CATALOG",
            "DRAFT", "PUBLISHED", payload, correlation, actor.userId(), at);
        record(tenant, "PUBLISH_CATALOG", key, payload.sha256(), "CATALOG", current.catalogId(), "PUBLISHED", at);
        return catalogDetail(tenant, current.catalogId());
    }

    /** 从已发布目录冻结项目检查项，后续目录版本不回写项目。 */
    @Transactional
    public ProjectDetail createProject(CreateProject command) {
        TrustedPrincipal actor = requireStore(command.storeId()); String tenant = actor.tenantId();
        String key = ServiceRules.idempotencyKey(command.idempotencyKey()); String correlation = correlation(command.correlationId());
        CatalogRecord catalog = requireCatalog(tenant, command.catalogId(), false);
        if (!"PUBLISHED".equals(catalog.state())) throw conflict("SVC-CAT-004", "项目只能绑定已发布目录");
        if (command.targetDate() == null) throw bad("SVC-PROJ-001", "目标日期不能为空");
        Long owner = command.ownerUserId() == null ? actor.userId() : command.ownerUserId();
        CanonicalJson.Result payload = canonical(Map.of("storeId", command.storeId(), "catalogId", catalog.catalogId(),
            "catalogSha256", catalog.contentSha256(), "targetDate", command.targetDate().toString(), "ownerUserId", owner));
        CommandRecord replay = replay(tenant, "CREATE_PROJECT", key, payload.sha256());
        if (replay != null) return projectDetail(tenant, replay.resultId());
        List<CatalogItemRecord> items = persistence.listCatalogItems(tenant, catalog.catalogId());
        LocalDateTime at = now(); String projectId = ids.next();
        persistence.insertProject(new ProjectWrite(projectId, tenant, command.storeId(), catalog.catalogId(), "DRAFT",
            owner, command.targetDate(), payload.sha256(), at));
        for (CatalogItemRecord item : items) persistence.insertProjectCheck(new ProjectCheckWrite(ids.next(), tenant,
            projectId, item.itemId(), item.itemCode(), item.itemName(), item.mandatory(), item.sequenceNo(), at));
        appendEvidence(tenant, command.storeId(), "PROJECT", projectId, 1, "CREATE_PROJECT", null, "DRAFT",
            payload, correlation, actor.userId(), at);
        record(tenant, "CREATE_PROJECT", key, payload.sha256(), "PROJECT", projectId, "DRAFT", at);
        return projectDetail(tenant, projectId);
    }

    /** 项目状态迁移；移交冻结点强制核对全部必选项。 */
    @Transactional
    public ProjectDetail commandProject(ProjectCommand command) {
        String tenant = tenantContext.requireTenantId(); ProjectRecord before = requireProject(tenant, command.projectId(), true);
        TrustedPrincipal actor = requireStore(before.storeId()); String operation = "PROJECT_" + ServiceRules.code(command.command(), "command");
        String key = ServiceRules.idempotencyKey(command.idempotencyKey()); String correlation = correlation(command.correlationId());
        String reason = ServiceRules.text(command.reason(), "reason", 500);
        if (command.expectedVersion() == null || command.expectedVersion() < 0) throw bad("SVC-CONC-001", "项目版本不能为空");
        CanonicalJson.Result payload = canonical(Map.of("projectId", before.projectId(), "command", operation,
            "reason", reason, "expectedVersion", command.expectedVersion()));
        CommandRecord replay = replay(tenant, operation, key, payload.sha256());
        if (replay != null) return projectDetail(tenant, replay.resultId());
        if (!Objects.equals(before.recordVersion(), command.expectedVersion())) throw conflict("SVC-CONC-001", "项目版本冲突");
        String target = projectTarget(command.command());
        ServiceStates.requireProjectTransition(before.state(), target);
        if ("READY_TO_HANDOVER".equals(target) && persistence.countMandatoryIncomplete(tenant, before.projectId()) != 0)
            throw conflict("SVC-CHECK-001", "必选检查项未全部完成");
        LocalDateTime at = now();
        if (persistence.changeProjectState(new ProjectStateChange(tenant, before.projectId(), before.state(), target,
            before.recordVersion(), payload.sha256(), at)) != 1) throw conflict("SVC-CONC-001", "项目状态并发冲突");
        appendEvidence(tenant, before.storeId(), "PROJECT", before.projectId(), before.recordVersion() + 1,
            operation, before.state(), target, payload, correlation, actor.userId(), at);
        record(tenant, operation, key, payload.sha256(), "PROJECT", before.projectId(), target, at);
        return projectDetail(tenant, before.projectId());
    }

    /** 完成冻结检查项；已完成事实不允许覆盖。 */
    @Transactional
    public ProjectDetail completeCheck(CompleteCheck command) {
        String tenant = tenantContext.requireTenantId(); ProjectRecord project = requireProject(tenant, command.projectId(), true);
        TrustedPrincipal actor = requireStore(project.storeId()); String key = ServiceRules.idempotencyKey(command.idempotencyKey());
        String correlation = correlation(command.correlationId()); String note = ServiceRules.text(command.reason(), "reason", 500);
        if (command.expectedVersion() == null || command.expectedVersion() < 0) throw bad("SVC-CONC-001", "检查项版本不能为空");
        CanonicalJson.Result payload = canonical(Map.of("projectId", project.projectId(), "checkId", command.checkId(),
            "note", note, "expectedVersion", command.expectedVersion()));
        CommandRecord replay = replay(tenant, "COMPLETE_PROJECT_CHECK", key, payload.sha256());
        if (replay != null) return projectDetail(tenant, replay.resultId());
        if (!Set.of("IN_PROGRESS", "BLOCKED").contains(project.state())) throw conflict("SVC-STATE-001", "当前项目状态不能完成检查项");
        if (persistence.completeProjectCheck(new ProjectCheckComplete(tenant, project.projectId(), command.checkId(),
            command.expectedVersion(), actor.userId(), note, now())) != 1) throw conflict("SVC-CONC-001", "检查项状态或版本冲突");
        LocalDateTime at = now();
        appendEvidence(tenant, project.storeId(), "PROJECT", project.projectId(), project.recordVersion(),
            "COMPLETE_PROJECT_CHECK", project.state(), project.state(), payload, correlation, actor.userId(), at);
        record(tenant, "COMPLETE_PROJECT_CHECK", key, payload.sha256(), "PROJECT", project.projectId(), project.state(), at);
        return projectDetail(tenant, project.projectId());
    }

    /** 创建 OPEN 工单；内部目标是服务端 UTC 快照，不形成商业 SLA。 */
    @Transactional
    public TicketDetail createTicket(CreateTicket command) {
        TrustedPrincipal actor = requireStore(command.storeId()); String tenant = actor.tenantId();
        String key = ServiceRules.idempotencyKey(command.idempotencyKey()); String correlation = correlation(command.correlationId());
        String type = ServiceRules.code(command.serviceType(), "serviceType"); String priority = ServiceRules.code(command.priority(), "priority");
        if (!PRIORITIES.contains(priority)) throw bad("SVC-TICKET-001", "工单优先级非法");
        if (command.projectId() != null) {
            ProjectRecord project = requireProject(tenant, command.projectId(), false);
            if (!project.storeId().equals(command.storeId())) throw conflict("SVC-TENANT-001", "工单项目门店不匹配");
        }
        String subject = ServiceRules.text(command.subject(), "subject", 200);
        String description = command.description() == null ? "" : ServiceRules.text(command.description(), "description", 2000);
        int targetMinutes = ServiceRules.targetMinutes(command.internalTargetMinutes());
        CanonicalJson.Result payload = canonical(Map.of("storeId", command.storeId(), "projectId", nullToEmpty(command.projectId()),
            "serviceType", type, "priority", priority, "subject", subject, "description", description,
            "internalTargetMinutes", targetMinutes));
        CommandRecord replay = replay(tenant, "CREATE_TICKET", key, payload.sha256());
        if (replay != null) return ticketDetail(tenant, replay.resultId());
        LocalDateTime at = now(); String id = ids.next();
        persistence.insertTicket(new TicketWrite(id, tenant, command.storeId(), command.projectId(), type, priority,
            subject, description, "OPEN", at.plusMinutes(targetMinutes), payload.sha256(), actor.userId(), at));
        appendEvidence(tenant, command.storeId(), "TICKET", id, 1, "CREATE_TICKET", null, "OPEN",
            payload, correlation, actor.userId(), at);
        record(tenant, "CREATE_TICKET", key, payload.sha256(), "TICKET", id, "OPEN", at);
        return ticketDetail(tenant, id);
    }

    /** 工单认领、转派、处理、解决、独立关闭和重开的统一具名编排。 */
    @Transactional
    public TicketDetail commandTicket(TicketCommand command) {
        String tenant = tenantContext.requireTenantId(); TicketRecord before = requireTicket(tenant, command.ticketId(), true);
        TrustedPrincipal actor = requireStore(before.storeId()); String action = ServiceRules.code(command.command(), "command");
        String operation = "TICKET_" + action; String key = ServiceRules.idempotencyKey(command.idempotencyKey());
        String correlation = correlation(command.correlationId()); String reason = ServiceRules.text(command.reason(), "reason", 1000);
        if (command.expectedVersion() == null || command.expectedVersion() < 0) throw bad("SVC-CONC-001", "工单版本不能为空");
        CanonicalJson.Result payload = canonical(ticketPayload(command, operation, reason));
        CommandRecord replay = replay(tenant, operation, key, payload.sha256());
        if (replay != null) return ticketDetail(tenant, replay.resultId());
        if (!Objects.equals(before.recordVersion(), command.expectedVersion())) throw conflict("SVC-CONC-001", "工单版本冲突");
        TicketMutation mutation = ticketMutation(before, action, command, actor.userId());
        ServiceStates.requireTicketTransition(before.state(), mutation.targetState()); LocalDateTime at = now();
        if (persistence.changeTicket(new TicketChange(tenant, before.ticketId(), before.state(), mutation.targetState(),
            before.recordVersion(), mutation.assignee(), mutation.leaseUntil(), mutation.resolvedBy(), mutation.closedBy(),
            mutation.resolution(), payload.sha256(), at)) != 1) throw conflict("SVC-CONC-001", "工单状态、责任或租约并发冲突");
        persistence.appendHistory(new HistoryWrite(ids.next(), tenant, before.storeId(), "TICKET", before.ticketId(),
            operation, before.state(), mutation.targetState(), before.assigneeUserId(), mutation.assignee(), reason,
            payload.sha256(), correlation, actor.userId(), at));
        appendAuditOutbox(tenant, before.storeId(), "TICKET", before.ticketId(), before.recordVersion() + 1,
            operation, mutation.targetState(), payload, correlation, actor.userId(), at);
        record(tenant, operation, key, payload.sha256(), "TICKET", before.ticketId(), mutation.targetState(), at);
        return ticketDetail(tenant, before.ticketId());
    }

    /** 附件正文经受限流式暂存后写入租户命名空间对象存储，数据库只保存元数据。 */
    @Transactional
    public AttachmentRecord uploadAttachment(String ticketId, String fileName, String mediaType, long declaredSize,
                                               InputStream content, String idempotencyKey, String correlationId) {
        String tenant = tenantContext.requireTenantId(); TicketRecord ticket = requireTicket(tenant, ticketId, true);
        TrustedPrincipal actor = requireStore(ticket.storeId());
        if (Set.of("CLOSED", "CANCELLED").contains(ticket.state())) throw conflict("SVC-STATE-001", "关闭或取消工单不能新增附件");
        if (content == null) throw bad("SVC-ATT-001", "附件正文为空");
        String safeName = ServiceRules.safeFileName(fileName); String type = ServiceRules.mediaType(mediaType);
        long expectedSize = ServiceRules.attachmentSize(declaredSize);
        try (StagedAttachment staged = storage.stage(content, expectedSize, ServiceRules.MAX_ATTACHMENT_BYTES)) {
            long size = ServiceRules.attachmentSize(staged.sizeBytes()); String sha = ServiceRules.sha256(staged.sha256());
            String key = ServiceRules.idempotencyKey(idempotencyKey); String correlation = correlation(correlationId);
            CanonicalJson.Result payload = canonical(Map.of("ticketId", ticketId, "fileName", safeName,
                "mediaType", type, "sizeBytes", size, "sha256", sha));
            CommandRecord replay = replay(tenant, "UPLOAD_ATTACHMENT", key, payload.sha256());
            if (replay != null) return requireAttachment(tenant, ticketId, replay.resultId()).toPublic();
            if (persistence.countAttachments(tenant, ticketId) >= 50) throw conflict("SVC-ATT-004", "单工单附件数量已达上限");
            LocalDateTime at = now(); String attachmentId = ids.next();
            String objectKey = ServiceRules.objectKey(tenant, ticketId, attachmentId);
            storage.store(new StoreObject(objectKey, staged, type, sha));
            registerObjectRollbackCleanup(objectKey);
            persistence.insertAttachment(new AttachmentWrite(attachmentId, tenant, ticket.storeId(), ticketId, objectKey,
                safeName, type, size, sha, "STORED", actor.userId(), at.plusYears(1), at));
            appendAuditOutbox(tenant, ticket.storeId(), "ATTACHMENT", attachmentId, 1, "UPLOAD_ATTACHMENT", "STORED",
                payload, correlation, actor.userId(), at);
            record(tenant, "UPLOAD_ATTACHMENT", key, payload.sha256(), "ATTACHMENT", attachmentId, "STORED", at);
            return requireAttachment(tenant, ticketId, attachmentId).toPublic();
        }
    }

    /** 每次签发短期下载地址前重新执行订阅、租户、门店和附件状态授权。 */
    @Transactional(readOnly = true)
    public AttachmentDownload issueDownload(String ticketId, String attachmentId) {
        String tenant = tenantContext.requireTenantId(); TicketRecord ticket = requireTicket(tenant, ticketId, false);
        requireStore(ticket.storeId()); AttachmentStored attachment = requireAttachment(tenant, ticketId, attachmentId);
        if (!"STORED".equals(attachment.state())) throw conflict("SVC-ATT-002", "附件当前不可下载");
        LocalDateTime expires = now().plus(ServiceRules.DOWNLOAD_TTL);
        return new AttachmentDownload(attachment.toPublic(), storage.temporaryDownload(attachment.objectKey(), ServiceRules.DOWNLOAD_TTL), expires);
    }

    /** 具名清理对象正文并保留摘要、元数据状态和审计；重复请求返回稳定结果。 */
    @Transactional
    public AttachmentRecord cleanAttachment(String ticketId, String attachmentId, String idempotencyKey, String correlationId) {
        String tenant = tenantContext.requireTenantId(); TicketRecord ticket = requireTicket(tenant, ticketId, true);
        TrustedPrincipal actor = requireStore(ticket.storeId()); String key = ServiceRules.idempotencyKey(idempotencyKey);
        String correlation = correlation(correlationId); AttachmentStored attachment = requireAttachment(tenant, ticketId, attachmentId);
        CanonicalJson.Result payload = canonical(Map.of("ticketId", ticketId, "attachmentId", attachmentId, "sha256", attachment.sha256()));
        CommandRecord replay = replay(tenant, "CLEAN_ATTACHMENT", key, payload.sha256());
        if (replay != null) return requireAttachment(tenant, ticketId, attachmentId).toPublic();
        if ("CLEANED".equals(attachment.state())) return attachment.toPublic();
        if (!"STORED".equals(attachment.state())) throw conflict("SVC-ATT-002", "附件状态不可清理");
        storage.delete(attachment.objectKey()); LocalDateTime at = now();
        if (persistence.changeAttachmentState(new AttachmentStateChange(tenant, ticketId, attachmentId,
            "STORED", "CLEANED", at, at)) != 1) throw conflict("SVC-CONC-001", "附件清理并发冲突");
        appendAuditOutbox(tenant, ticket.storeId(), "ATTACHMENT", attachmentId, 2, "CLEAN_ATTACHMENT", "CLEANED",
            payload, correlation, actor.userId(), at);
        record(tenant, "CLEAN_ATTACHMENT", key, payload.sha256(), "ATTACHMENT", attachmentId, "CLEANED", at);
        return requireAttachment(tenant, ticketId, attachmentId).toPublic();
    }

    @Transactional(readOnly = true) public List<ProjectRecord> listProjects(Long storeId, int limit) {
        TrustedPrincipal actor = requireStore(storeId); return persistence.listProjects(actor.tenantId(), storeId, bounded(limit));
    }
    @Transactional(readOnly = true) public ProjectDetail project(String projectId) {
        String tenant=tenantContext.requireTenantId();ProjectRecord project=requireProject(tenant,projectId,false);requireStore(project.storeId());return projectDetail(tenant,projectId);
    }
    @Transactional(readOnly = true) public List<TicketRecord> listTickets(Long storeId,String state,int limit) {
        TrustedPrincipal actor=requireStore(storeId);return persistence.listTickets(actor.tenantId(),storeId,state==null?null:ServiceRules.code(state,"state"),bounded(limit));
    }
    @Transactional(readOnly = true) public TicketDetail ticket(String ticketId) {
        String tenant=tenantContext.requireTenantId();TicketRecord ticket=requireTicket(tenant,ticketId,false);requireStore(ticket.storeId());return ticketDetail(tenant,ticketId);
    }

    private TrustedPrincipal requireTenantAdmin() { requireEntitlement(); authorization.requireTenantAdministrator(); return tenantContext.requirePrincipal(); }
    private TrustedPrincipal requireStore(Long storeId) { requireEntitlement(); authorization.requireStoreAccess(storeId); return tenantContext.requirePrincipal(); }
    private void requireEntitlement() { var decision=entitlements.decide(FEATURE); if(!decision.allowed())throw new ServiceException("SVC-ACCESS-001: SaaS或Subscription未授权服务运营",403); }
    private CatalogRecord requireCatalog(String tenant,String id,boolean lock){CatalogRecord value=lock?persistence.lockCatalog(tenant,id):persistence.findCatalog(tenant,id);if(value==null)throw notFound();return value;}
    private ProjectRecord requireProject(String tenant,String id,boolean lock){ProjectRecord value=lock?persistence.lockProject(tenant,id):persistence.findProject(tenant,id);if(value==null)throw notFound();return value;}
    private TicketRecord requireTicket(String tenant,String id,boolean lock){TicketRecord value=lock?persistence.lockTicket(tenant,id):persistence.findTicket(tenant,id);if(value==null)throw notFound();return value;}
    private CatalogDetail catalogDetail(String tenant,String id){CatalogRecord value=requireCatalog(tenant,id,false);return new CatalogDetail(value,persistence.listCatalogItems(tenant,id));}
    private ProjectDetail projectDetail(String tenant,String id){ProjectRecord value=requireProject(tenant,id,false);return new ProjectDetail(value,persistence.listProjectChecks(tenant,id));}
    private TicketDetail ticketDetail(String tenant,String id){TicketRecord value=requireTicket(tenant,id,false);return new TicketDetail(value,persistence.listAttachments(tenant,id),now().isAfter(value.targetAt())&&!Set.of("CLOSED","CANCELLED").contains(value.state()));}

    private List<CatalogItemInput> normalizeItems(List<CatalogItemInput> source){Set<String> codes=new HashSet<>();List<CatalogItemInput> result=new ArrayList<>();for(CatalogItemInput item:source){String code=ServiceRules.code(item.itemCode(),"itemCode");if(!codes.add(code))throw bad("SVC-CAT-005","目录检查项编码重复");int sequence=item.sequenceNo()==null?result.size()+1:item.sequenceNo();if(sequence<1||sequence>1000)throw bad("SVC-CAT-006","检查项顺序非法");result.add(new CatalogItemInput(code,ServiceRules.text(item.itemName(),"itemName",120),Boolean.TRUE.equals(item.mandatory()),sequence));}return List.copyOf(result);}
    private Map<String,Object> itemPayload(CatalogItemInput item){return Map.of("itemCode",item.itemCode(),"itemName",item.itemName(),"mandatory",item.mandatory(),"sequenceNo",item.sequenceNo());}
    private String projectTarget(String command){return switch(ServiceRules.code(command,"command")){case "PREFLIGHT"->"PREFLIGHTING";case "PREFLIGHT_FAILED"->"PREFLIGHT_FAILED";case "MARK_READY"->"READY";case "START"->"IN_PROGRESS";case "BLOCK"->"BLOCKED";case "UNBLOCK"->"IN_PROGRESS";case "READY_TO_HANDOVER"->"READY_TO_HANDOVER";case "HANDOVER"->"HANDED_OVER";case "CANCEL"->"CANCELLED";default->throw bad("SVC-STATE-002","未知项目命令");};}

    private TicketMutation ticketMutation(TicketRecord before,String action,TicketCommand command,Long actor){LocalDateTime at=now();Long assignee=before.assigneeUserId();LocalDateTime lease=before.leaseUntil();Long resolver=before.resolvedBy();Long closer=before.closedBy();String resolution=before.resolutionSummary();String target;
        switch(action){case "CLAIM"->{if(lease!=null&&lease.isAfter(at)&&!actor.equals(assignee))throw conflict("SVC-LEASE-003","工单已有有效认领租约");assignee=actor;lease=at.plusMinutes(ServiceRules.leaseMinutes(command.leaseMinutes()));target="ASSIGNED";}
            case "ASSIGN"->{if(command.assigneeUserId()==null||command.assigneeUserId()<1)throw bad("SVC-VAL-004","目标责任人非法");assignee=command.assigneeUserId();lease=at.plusMinutes(ServiceRules.leaseMinutes(command.leaseMinutes()));target="ASSIGNED";}
            case "START"->{ServiceRules.requireActiveLease(assignee,lease,actor,at);target="IN_PROGRESS";}
            case "WAIT_FOR_INPUT"->{ServiceRules.requireActiveLease(assignee,lease,actor,at);target="WAITING_INPUT";}
            case "RESOLVE"->{ServiceRules.requireActiveLease(assignee,lease,actor,at);resolution=ServiceRules.text(command.resolutionSummary(),"resolutionSummary",1000);resolver=actor;target="RESOLVED";}
            case "CLOSE"->{ServiceRules.requireIndependentReviewer(resolver,actor);closer=actor;lease=null;target="CLOSED";}
            case "REOPEN"->{assignee=null;lease=null;target="REOPENED";}
            case "CANCEL"->{lease=null;target="CANCELLED";}
            default->throw bad("SVC-STATE-002","未知工单命令");}
        return new TicketMutation(target,assignee,lease,resolver,closer,resolution);}
    private Map<String,Object> ticketPayload(TicketCommand c,String operation,String reason){Map<String,Object> map=new LinkedHashMap<>();map.put("ticketId",c.ticketId());map.put("operation",operation);map.put("assigneeUserId",c.assigneeUserId());map.put("leaseMinutes",c.leaseMinutes());map.put("reason",reason);map.put("resolutionSummary",c.resolutionSummary());map.put("expectedVersion",c.expectedVersion());return map;}

    private void appendEvidence(String tenant,Long store,String type,String id,int version,String action,String from,String to,CanonicalJson.Result payload,String correlation,Long actor,LocalDateTime at){persistence.appendHistory(new HistoryWrite(ids.next(),tenant,store,type,id,action,from,to,null,null,"具名状态事实",payload.sha256(),correlation,actor,at));appendAuditOutbox(tenant,store,type,id,version,action,to,payload,correlation,actor,at);}
    private void appendAuditOutbox(String tenant,Long store,String type,String id,int version,String action,String state,CanonicalJson.Result payload,String correlation,Long actor,LocalDateTime at){
        persistence.appendAudit(new AuditWrite(ids.next(),tenant,store,type,id,action,"SUCCESS",payload.sha256(),correlation,actor,"状态="+state,at));
        String eventId=ids.next();String eventType=eventType(type,action);
        Map<String,Object> envelope=new LinkedHashMap<>();envelope.put("schemaVersion","1.0");envelope.put("eventId",eventId);
        envelope.put("eventType",eventType);envelope.put("tenantId",tenant);envelope.put("aggregateType",type);
        envelope.put("aggregateId",id);envelope.put("aggregateVersion",version);envelope.put("storeId",store);
        envelope.put("payloadSha256",payload.sha256());envelope.put("correlationId",correlation);
        envelope.put("occurredAt",at.toInstant(ZoneOffset.UTC).toString());
        CanonicalJson.Result event=canonical(envelope);
        persistence.appendOutbox(new OutboxWrite(eventId,tenant,type,id,version,eventType,event.json(),event.sha256(),correlation,at));
    }
    private String eventType(String type,String action){
        if("CATALOG".equals(type))return "CREATE_CATALOG".equals(action)?"service.catalog-created.v1":"service.catalog-published.v1";
        if("PROJECT".equals(type)&&"CREATE_PROJECT".equals(action))return "service.project-created.v1";
        if("PROJECT".equals(type)&&"COMPLETE_PROJECT_CHECK".equals(action))return "service.project-check-completed.v1";
        if("PROJECT".equals(type))return "service.project-state-changed.v1";
        if("ATTACHMENT".equals(type)&&"CLEAN_ATTACHMENT".equals(action))return "service.attachment-cleaned.v1";
        if("ATTACHMENT".equals(type))return "service.attachment-stored.v1";
        if("CREATE_TICKET".equals(action))return "service.ticket-created.v1";
        return action.matches(".*(CLAIM|ASSIGN).*")?"service.ticket-assignment-changed.v1":"service.ticket-state-changed.v1";
    }
    private CommandRecord replay(String tenant,String operation,String key,String hash){CommandRecord value=persistence.findCommand(tenant,operation,key);if(value!=null&&!value.requestSha256().equals(hash))throw conflict("SVC-IDEM-001","同幂等键对应不同内容");return value;}
    private void record(String tenant,String operation,String key,String hash,String type,String id,String state,LocalDateTime at){persistence.appendCommand(new CommandWrite(ids.next(),tenant,operation,key,hash,type,id,state,at));}
    private CanonicalJson.Result canonical(Map<String,?> source){Map<String,Object> normalized=new LinkedHashMap<>();source.forEach((k,v)->normalized.put(k,canonicalValue(v)));return CanonicalJson.from(normalized);}
    private Object canonicalValue(Object value){if(value==null)return "";if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC).toString();return value;}
    private String correlation(String value){String result=ServiceRules.required(value,"correlationId");if(!result.matches("^[A-Za-z0-9._:-]{1,64}$"))throw bad("SVC-CORR-001","关联标识格式非法");return result;}
    private int bounded(int limit){return Math.max(1,Math.min(limit,100));}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private String nullToEmpty(String value){return value==null?"":value;}
    private AttachmentStored requireAttachment(String tenant,String ticket,String id){AttachmentStoredRecord value=persistence.findAttachment(tenant,ticket,id);if(value==null)throw notFound();return new AttachmentStored(value);}
    /** 对象正文先于数据库元数据写入时，事务回滚必须尽力删除确定性对象键，避免孤儿正文。 */
    private void registerObjectRollbackCleanup(String objectKey){
        if(!TransactionSynchronizationManager.isSynchronizationActive())return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){
                if(status==TransactionSynchronization.STATUS_COMMITTED)return;
                try{storage.delete(objectKey);}catch(RuntimeException exception){log.error("服务附件事务回滚补偿失败 objectKey={}",objectKey);}
            }
        });
    }
    private static ServiceException notFound(){return new ServiceException("SVC-404: 服务事实不存在",404);}
    private static ServiceException conflict(String code,String message){return new ServiceException(code+": "+message,409);}
    private static ServiceException bad(String code,String message){return new ServiceException(code+": "+message,400);}
    /**
     * @param targetState 目标状态
     * @param assignee 责任人
     * @param leaseUntil 认领租约截止时间
     * @param resolvedBy 解决人
     * @param closedBy 独立复核关闭人
     * @param resolution 解决摘要
     */
    private record TicketMutation(String targetState, Long assignee, LocalDateTime leaseUntil,
                                  Long resolvedBy, Long closedBy, String resolution) { }

    /** @param value 仅供应用层使用、包含受控对象键的附件持久化记录 */
    private record AttachmentStored(AttachmentStoredRecord value) {
        String objectKey() { return value.objectKey(); }
        String state() { return value.state(); }
        String sha256() { return value.sha256(); }
        AttachmentRecord toPublic() {
            return new AttachmentRecord(value.attachmentId(), value.fileName(), value.mediaType(),
                value.sizeBytes(), value.sha256(), value.state(), value.createdAt());
        }
    }
}
