package com.jingshanghui.pos.service.e2e;

import com.jingshanghui.pos.saas.application.model.SaasModels.ApplicationDetail;
import com.jingshanghui.pos.saas.application.model.SaasModels.ApplicationRecord;
import com.jingshanghui.pos.saas.application.model.SaasModels.EntitlementVersionRecord;
import com.jingshanghui.pos.saas.application.model.SaasModels.TenantEntitlementRecord;
import com.jingshanghui.pos.saas.application.service.SaasApplicationService;
import com.jingshanghui.pos.saas.infrastructure.persistence.entity.SaasPlanEntity;
import com.jingshanghui.pos.saas.interfaces.rest.SaasOperationsController;
import com.jingshanghui.pos.service.application.model.ServiceModels.AttachmentDownload;
import com.jingshanghui.pos.service.application.model.ServiceModels.AttachmentRecord;
import com.jingshanghui.pos.service.application.model.ServiceModels.CatalogDetail;
import com.jingshanghui.pos.service.application.model.ServiceModels.CatalogItemRecord;
import com.jingshanghui.pos.service.application.model.ServiceModels.CatalogRecord;
import com.jingshanghui.pos.service.application.model.ServiceModels.CheckRecord;
import com.jingshanghui.pos.service.application.model.ServiceModels.ProjectDetail;
import com.jingshanghui.pos.service.application.model.ServiceModels.ProjectRecord;
import com.jingshanghui.pos.service.application.model.ServiceModels.TicketDetail;
import com.jingshanghui.pos.service.application.model.ServiceModels.TicketRecord;
import com.jingshanghui.pos.service.application.service.ServiceApplicationService;
import com.jingshanghui.pos.service.interfaces.rest.ServiceOperationsController;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.SubscriptionDetail;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.SubscriptionRecord;
import com.jingshanghui.pos.subscription.application.service.SubscriptionApplicationService;
import com.jingshanghui.pos.subscription.interfaces.rest.SubscriptionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate 8B-Prep 商业 SaaS 运营正式 REST 旅程。
 *
 * <p>测试只经三个正式 Controller 进入既有 Owner 应用服务，不访问数据库、不增加业务后门。
 * 各 Owner 的状态机、持久化与租户失败关闭继续由其已接受测试和 MySQL 门禁证明；本测试只验证
 * 跨 Owner 旅程的 HTTP 路径、请求头、DTO、返回状态与稳定身份能够按冻结顺序装配。</p>
 */
class CommercialSaasOperationsFormalApiE2ETest {
    private static final String APPLICATION_ID = "01K00000000000000000000001";
    private static final String VERSION_ID = "01K00000000000000000000002";
    private static final String SUBSCRIPTION_ID = "01K00000000000000000000003";
    private static final String CATALOG_ID = "01K00000000000000000000004";
    private static final String ITEM_ID = "01K00000000000000000000005";
    private static final String PROJECT_ID = "01K00000000000000000000006";
    private static final String CHECK_ID = "01K00000000000000000000007";
    private static final String TICKET_ID = "01K00000000000000000000008";
    private static final String ATTACHMENT_ID = "01K00000000000000000000009";
    private static final String TENANT_ID = "200001";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 0, 0);

    private SaasApplicationService saas;
    private SubscriptionApplicationService subscription;
    private ServiceApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        saas = mock(SaasApplicationService.class);
        subscription = mock(SubscriptionApplicationService.class);
        service = mock(ServiceApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
            new SaasOperationsController(saas),
            new SubscriptionController(subscription),
            new ServiceOperationsController(service)
        ).build();
        arrangeOwnerResults();
    }

    @Test
    void completesAcceptedCommercialOperationsJourneyThroughFormalRestApi() throws Exception {
        createAndActivatePlanAndTenant();
        createRenewDegradeAndRestoreSubscription();
        completeImplementationAndServiceJourney();
        deactivateAndRecoverCommercialTenant();

        verify(saas).createApplication(any());
        verify(subscription).create(any());
        verify(service).createProject(any());
        verify(service).uploadAttachment(any(), any(), any(), anyLong(), any(), any(), any());
        verify(saas).deactivate(any());
        verify(saas).restore(any());
    }

    private void createAndActivatePlanAndTenant() throws Exception {
        perform(post("/api/v1/saas/plans"), "plan-create-001", "trace-plan-001",
            "{\"planCode\":\"V1_STANDARD\",\"planName\":\"虚构标准套餐\",\"platformPackageId\":8,\"accountLimit\":50}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.planId").value(1));
        perform(post("/api/v1/saas/plans/1/versions"), "version-create-001", "trace-version-001",
            "{\"versionNo\":1,\"effectiveAt\":\"2026-08-24T00:00:00\",\"items\":[{\"featureCode\":\"SERVICE_OPERATIONS\",\"enabled\":true,\"quotaLimit\":100}]}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("DRAFT"));
        advanceEntitlement("validate", "READY", 1);
        advanceEntitlement("approve", "APPROVED", 2);
        advanceEntitlement("publish", "PUBLISHED", 3);
        advanceEntitlement("activate", "EFFECTIVE", 4);

        perform(post("/api/v1/saas/applications"), "application-create-001", "trace-application-001",
            "{\"applicationCode\":\"APP-SYN-001\",\"companyName\":\"虚构便利商户\",\"industry\":\"CONVENIENCE\",\"planId\":1}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.application.state").value("DRAFT"));
        perform(post("/api/v1/saas/applications/{id}/preflight", APPLICATION_ID), "application-preflight-001",
            "trace-application-002", null).andExpect(jsonPath("$.data.application.state").value("READY"));
        perform(post("/api/v1/saas/applications/{id}/approve", APPLICATION_ID), "application-approve-001",
            "trace-application-003", "{\"reason\":\"独立复核通过\"}")
            .andExpect(jsonPath("$.data.application.state").value("APPROVED"));
        perform(post("/api/v1/saas/applications/{id}/provision", APPLICATION_ID), "application-provision-001",
            "trace-application-004", "{\"contactName\":\"虚构联系人\",\"contactPhone\":\"00000000000\",\"bootstrapUsername\":\"synthetic\",\"bootstrapPassword\":\"Synthetic-Pass-01\"}")
            .andExpect(jsonPath("$.data.application.tenantId").value(TENANT_ID));
        perform(post("/api/v1/saas/applications/{id}/initialize", APPLICATION_ID), "application-initialize-001",
            "trace-application-005", null).andExpect(jsonPath("$.data.application.state").value("INITIALIZING"));
        perform(post("/api/v1/saas/applications/{id}/activate", APPLICATION_ID), "application-activate-001",
            "trace-application-006", null).andExpect(jsonPath("$.data.application.state").value("ACTIVE"));
    }

    private void createRenewDegradeAndRestoreSubscription() throws Exception {
        String firstTerm = "{\"contractRef\":\"CONTRACT-001\",\"externalOrderRef\":\"ORDER-001\",\"startsAt\":\"2026-08-24T00:00:00\",\"endsAt\":\"2026-09-24T00:00:00\",\"graceEndsAt\":\"2026-10-01T00:00:00\",\"businessTimeZone\":\"Asia/Shanghai\",\"degradationPolicyVersion\":\"RECOVERY-V1\"}";
        perform(post("/api/v1/subscriptions/tenants/{tenant}", TENANT_ID), "subscription-create-001",
            "trace-subscription-001", firstTerm).andExpect(jsonPath("$.data.subscription.state").value("DRAFT"));
        perform(post("/api/v1/subscriptions/{id}/activate", SUBSCRIPTION_ID), "subscription-activate-001",
            "trace-subscription-002", null).andExpect(jsonPath("$.data.accessMode").value("NORMAL"));
        String secondTerm = "{\"contractRef\":\"CONTRACT-002\",\"externalOrderRef\":\"ORDER-002\",\"startsAt\":\"2026-09-24T00:00:00\",\"endsAt\":\"2026-10-24T00:00:00\",\"graceEndsAt\":\"2026-10-31T00:00:00\",\"businessTimeZone\":\"Asia/Shanghai\"}";
        perform(post("/api/v1/subscriptions/{id}/renew", SUBSCRIPTION_ID), "subscription-renew-001",
            "trace-subscription-003", secondTerm).andExpect(jsonPath("$.data.subscription.currentTermVersion").value(2));
        perform(post("/api/v1/subscriptions/{id}/suspend", SUBSCRIPTION_ID), "subscription-suspend-001",
            "trace-subscription-004", "{\"reason\":\"内部合成降级演练\"}")
            .andExpect(jsonPath("$.data.accessMode").value("RECOVERY_ONLY"));
        perform(post("/api/v1/subscriptions/{id}/restore", SUBSCRIPTION_ID), "subscription-restore-001",
            "trace-subscription-005", secondTerm).andExpect(jsonPath("$.data.subscription.state").value("ACTIVE"));
    }

    private void completeImplementationAndServiceJourney() throws Exception {
        perform(post("/api/v1/service/catalogs"), "catalog-create-001", "trace-service-001",
            "{\"catalogCode\":\"OPENING_V1\",\"versionNo\":1,\"industryTemplate\":\"CONVENIENCE\",\"name\":\"虚构开店检查\",\"items\":[{\"itemCode\":\"CONFIG_READY\",\"itemName\":\"配置已复核\",\"mandatory\":true,\"sequenceNo\":1}]}")
            .andExpect(jsonPath("$.data.catalog.state").value("DRAFT"));
        perform(post("/api/v1/service/catalogs/{id}/publish", CATALOG_ID), "catalog-publish-001",
            "trace-service-002", null).andExpect(jsonPath("$.data.catalog.state").value("PUBLISHED"));
        perform(post("/api/v1/service/projects"), "project-create-001", "trace-service-003",
            "{\"storeId\":1001,\"catalogId\":\"" + CATALOG_ID + "\",\"targetDate\":\"2026-08-31\",\"ownerUserId\":100}")
            .andExpect(jsonPath("$.data.project.state").value("DRAFT"));
        perform(versioned(post("/api/v1/service/projects/{project}/checks/{check}/complete", PROJECT_ID, CHECK_ID), 0),
            "project-check-001", "trace-service-004", "{\"reason\":\"合成检查完成\"}")
            .andExpect(jsonPath("$.data.checks[0].state").value("COMPLETED"));
        perform(versioned(post("/api/v1/service/projects/{id}/commands", PROJECT_ID), 1), "project-start-001",
            "trace-service-005", "{\"command\":\"START\",\"reason\":\"开始内部实施\"}")
            .andExpect(jsonPath("$.data.project.state").value("IN_PROGRESS"));

        perform(post("/api/v1/service/tickets"), "ticket-create-001", "trace-service-006",
            "{\"storeId\":1001,\"projectId\":\"" + PROJECT_ID + "\",\"serviceType\":\"IMPLEMENTATION_SUPPORT\",\"priority\":\"P2\",\"subject\":\"虚构实施工单\",\"description\":\"仅合成资料\",\"internalTargetMinutes\":240}")
            .andExpect(jsonPath("$.data.ticket.state").value("OPEN"));
        ticketCommand("CLAIM", 0, "ASSIGNED", "ticket-claim-001", "null", "30");
        ticketCommand("START", 1, "IN_PROGRESS", "ticket-start-001", "null", "30");

        MockMultipartFile file = new MockMultipartFile("file", "evidence.txt", "text/plain", "synthetic evidence".getBytes());
        mvc.perform(multipart("/api/v1/service/tickets/{ticket}/attachments", TICKET_ID).file(file)
                .header("Idempotency-Key", "attachment-upload-001").header("X-Correlation-ID", "trace-service-011"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("STORED"));
        perform(post("/api/v1/service/tickets/{ticket}/attachments/{attachment}/download", TICKET_ID, ATTACHMENT_ID),
            null, null, null).andExpect(jsonPath("$.data.attachment.attachmentId").value(ATTACHMENT_ID));
        perform(post("/api/v1/service/tickets/{ticket}/attachments/{attachment}/cleanup", TICKET_ID, ATTACHMENT_ID),
            "attachment-clean-001", "trace-service-012", null).andExpect(jsonPath("$.data.state").value("CLEANED"));
        ticketCommand("RESOLVE", 2, "RESOLVED", "ticket-resolve-001", "\"内部合成问题已解决\"", "30");
        ticketCommand("CLOSE", 3, "CLOSED", "ticket-close-001", "\"独立复核关闭\"", "null");
    }

    private void deactivateAndRecoverCommercialTenant() throws Exception {
        perform(post("/api/v1/saas/tenants/{tenant}/deactivate", TENANT_ID), "tenant-deactivate-001",
            "trace-tenant-001", "{\"reason\":\"内部受控停用演练\"}")
            .andExpect(jsonPath("$.data.lifecycleState").value("DEACTIVATED"));
        perform(post("/api/v1/saas/tenants/{tenant}/restore", TENANT_ID), "tenant-restore-001",
            "trace-tenant-002", "{\"reason\":\"内部受控恢复演练\"}")
            .andExpect(jsonPath("$.data.lifecycleState").value("ACTIVE"));
    }

    private void advanceEntitlement(String action, String state, int sequence) throws Exception {
        perform(post("/api/v1/saas/entitlements/{id}/{action}", VERSION_ID, action),
            "entitlement-" + action + "-001", "trace-entitlement-00" + sequence, null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value(state));
    }

    private void ticketCommand(String command, int version, String state, String key,
                               String resolution, String leaseMinutes) throws Exception {
        String body = "{\"command\":\"" + command + "\",\"leaseMinutes\":" + leaseMinutes
            + ",\"reason\":\"内部合成操作\",\"resolutionSummary\":" + resolution + "}";
        perform(versioned(post("/api/v1/service/tickets/{id}/commands", TICKET_ID), version), key,
            "trace-" + key, body).andExpect(jsonPath("$.data.ticket.state").value(state));
    }

    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request,
                                                                        String key,
                                                                        String correlation,
                                                                        String json) throws Exception {
        if (key != null) request.header("Idempotency-Key", key);
        if (correlation != null) request.header("X-Correlation-ID", correlation);
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder versioned(MockHttpServletRequestBuilder request, int version) {
        return request.header("If-Match-Version", version);
    }

    private void arrangeOwnerResults() {
        SaasPlanEntity plan = new SaasPlanEntity();
        plan.setPlanId(1L); plan.setPlanCode("V1_STANDARD"); plan.setPlanName("虚构标准套餐"); plan.setStatus("ACTIVE");
        when(saas.createPlan(any())).thenReturn(plan);
        when(saas.createVersion(any())).thenReturn(entitlement("DRAFT", 0));
        when(saas.validateVersion(any())).thenReturn(entitlement("READY", 1));
        when(saas.approveVersion(any())).thenReturn(entitlement("APPROVED", 2));
        when(saas.publishVersion(any())).thenReturn(entitlement("PUBLISHED", 3));
        when(saas.activateVersion(any())).thenReturn(entitlement("EFFECTIVE", 4));
        when(saas.createApplication(any())).thenReturn(application("DRAFT", null, 0));
        when(saas.preflight(any())).thenReturn(application("READY", null, 1));
        when(saas.approve(any())).thenReturn(application("APPROVED", null, 2));
        when(saas.provision(any())).thenReturn(application("PROVISIONING", TENANT_ID, 3));
        when(saas.initialize(any())).thenReturn(application("INITIALIZING", TENANT_ID, 4));
        when(saas.activate(any())).thenReturn(application("ACTIVE", TENANT_ID, 5));
        when(saas.deactivate(any())).thenReturn(lifecycle("DEACTIVATED", 6));
        when(saas.restore(any())).thenReturn(lifecycle("ACTIVE", 7));

        when(subscription.create(any())).thenReturn(subscription("DRAFT", 0, 1, "NO_ACCESS_EFFECT"));
        when(subscription.activate(any())).thenReturn(subscription("ACTIVE", 2, 1, "NORMAL"));
        when(subscription.renew(any())).thenReturn(subscription("ACTIVE", 3, 2, "NORMAL"));
        when(subscription.suspend(any())).thenReturn(subscription("SUSPENDED", 4, 2, "RECOVERY_ONLY"));
        when(subscription.restore(any())).thenReturn(subscription("ACTIVE", 6, 3, "NORMAL"));

        when(service.createCatalog(any())).thenReturn(catalog("DRAFT", 0));
        when(service.publishCatalog(any())).thenReturn(catalog("PUBLISHED", 1));
        when(service.createProject(any())).thenReturn(project("DRAFT", "PENDING", 0));
        when(service.completeCheck(any())).thenReturn(project("DRAFT", "COMPLETED", 1));
        when(service.commandProject(any())).thenReturn(project("IN_PROGRESS", "COMPLETED", 2));
        when(service.createTicket(any())).thenReturn(ticket("OPEN", 0));
        when(service.commandTicket(any())).thenReturn(ticket("ASSIGNED", 1), ticket("IN_PROGRESS", 2),
            ticket("RESOLVED", 3), ticket("CLOSED", 4));
        AttachmentRecord stored = attachment("STORED");
        when(service.uploadAttachment(any(), any(), any(), anyLong(), any(), any(), any())).thenReturn(stored);
        when(service.issueDownload(TICKET_ID, ATTACHMENT_ID)).thenReturn(
            new AttachmentDownload(stored, "https://object.invalid/short-lived", NOW.plusMinutes(5)));
        when(service.cleanAttachment(any(), any(), any(), any())).thenReturn(attachment("CLEANED"));
    }

    private EntitlementVersionRecord entitlement(String state, int version) {
        return new EntitlementVersionRecord(VERSION_ID, 1L, 1, state, NOW, null, "a".repeat(64),
            10L, 20L, version, NOW, NOW);
    }

    private ApplicationDetail application(String state, String tenantId, int version) {
        ApplicationRecord application = new ApplicationRecord(APPLICATION_ID, "APP-SYN-001", tenantId,
            tenantId == null ? null : 91L, "虚构便利商户", "CONVENIENCE", 1L, state, 10L, 20L,
            version, "b".repeat(64), NOW, NOW);
        TenantEntitlementRecord lifecycle = tenantId == null ? null : lifecycle("ACTIVE", version);
        return new ApplicationDetail(application, tenantId == null ? List.of() : List.of("TECHNICAL_TENANT", "ENTITLEMENT_BINDING"), lifecycle);
    }

    private TenantEntitlementRecord lifecycle(String state, int version) {
        return new TenantEntitlementRecord(TENANT_ID, 1L, VERSION_ID, state, version, NOW);
    }

    private SubscriptionDetail subscription(String state, int stateVersion, int termVersion, String accessMode) {
        SubscriptionRecord record = new SubscriptionRecord(SUBSCRIPTION_ID, TENANT_ID, 1L, VERSION_ID,
            "CONTRACT-001", "ORDER-001", state, stateVersion, termVersion, NOW, NOW.plusMonths(1),
            NOW.plusMonths(1).plusDays(7), "Asia/Shanghai", "RECOVERY-V1", "c".repeat(64), NOW, NOW);
        return new SubscriptionDetail(record, List.of(), accessMode,
            "RECOVERY_ONLY".equals(accessMode) ? List.of("REFUND", "RECONCILIATION", "AUDIT", "LEGAL_EXPORT") : List.of());
    }

    private CatalogDetail catalog(String state, int version) {
        return new CatalogDetail(new CatalogRecord(CATALOG_ID, TENANT_ID, "OPENING_V1", 1, "CONVENIENCE",
            "虚构开店检查", state, "d".repeat(64), version),
            List.of(new CatalogItemRecord(ITEM_ID, "CONFIG_READY", "配置已复核", true, 1)));
    }

    private ProjectDetail project(String state, String checkState, int version) {
        return new ProjectDetail(new ProjectRecord(PROJECT_ID, TENANT_ID, 1001L, CATALOG_ID, state, 100L,
            LocalDate.of(2026, 8, 31), version, "e".repeat(64)),
            List.of(new CheckRecord(CHECK_ID, "CONFIG_READY", "配置已复核", true, checkState,
                "COMPLETED".equals(checkState) ? 100L : null, "COMPLETED".equals(checkState) ? NOW : null, version)));
    }

    private TicketDetail ticket(String state, int version) {
        TicketRecord record = new TicketRecord(TICKET_ID, TENANT_ID, 1001L, PROJECT_ID,
            "IMPLEMENTATION_SUPPORT", "P2", "虚构实施工单", "仅合成资料", state,
            version > 0 ? 100L : null, version > 0 && version < 4 ? NOW.plusMinutes(30) : null,
            version >= 3 ? 100L : null, version >= 4 ? 200L : null,
            version >= 3 ? "内部合成问题已解决" : null, NOW.plusMinutes(240), version, "f".repeat(64));
        return new TicketDetail(record, List.of(), false);
    }

    private AttachmentRecord attachment(String state) {
        return new AttachmentRecord(ATTACHMENT_ID, "evidence.txt", "text/plain", 18L,
            "1".repeat(64), state, NOW);
    }
}
