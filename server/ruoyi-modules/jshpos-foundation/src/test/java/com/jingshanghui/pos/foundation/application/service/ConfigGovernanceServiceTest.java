package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.infrastructure.observability.FoundationMetrics;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigBindingEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigBindingMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigGovernanceServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "gate0-test"),
            ConfigTemplateVersionEntity.class
        );
    }

    private final ConfigTemplateMapper templates = mock(ConfigTemplateMapper.class);
    private final ConfigTemplateVersionMapper versions = mock(ConfigTemplateVersionMapper.class);
    private final ConfigBindingMapper bindings = mock(ConfigBindingMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final DomainAuditService audit = mock(DomainAuditService.class);
    private final FoundationMetrics metrics = mock(FoundationMetrics.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    private final ConfigGovernanceService service = new ConfigGovernanceService(
        templates, versions, bindings, context, authorization, audit, metrics, clock
    );

    @BeforeEach
    void setUp() {
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 10L, 1L, "alice"));
        when(templates.insert(any(ConfigTemplateEntity.class))).thenAnswer(invocation -> {
            ConfigTemplateEntity entity = invocation.getArgument(0);
            entity.setTemplateId(100L);
            return 1;
        });
        when(versions.insert(any(ConfigTemplateVersionEntity.class))).thenAnswer(invocation -> {
            ConfigTemplateVersionEntity entity = invocation.getArgument(0);
            entity.setConfigVersionId(1001L);
            return 1;
        });
        when(bindings.insert(any(ConfigBindingEntity.class))).thenAnswer(invocation -> {
            ConfigBindingEntity entity = invocation.getArgument(0);
            entity.setBindingId(500L);
            return 1;
        });
    }

    @Test
    void createsTemplateVersionPublishesAndActivatesTenantBinding() {
        var templateView = service.createTemplate(new ConfigGovernanceService.CreateTemplate(
            "convenience", "便利店模板", "convenience"));
        assertThat(templateView.templateId()).isEqualTo(100L);

        ConfigTemplateEntity template = template(100L, "ACTIVE");
        when(templates.selectById(100L)).thenReturn(template);
        when(versions.selectLatestForUpdate("TENANT_A", 100L)).thenReturn(null);
        var draft = service.createVersion(100L, new ConfigGovernanceService.CreateVersion(
            "1.0", Map.of("capabilities", Map.of("weightedGoods", false))));
        assertThat(draft.versionNo()).isEqualTo(1);
        assertThat(draft.contentSha256()).matches("[a-f0-9]{64}");

        ConfigTemplateVersionEntity version = version(1001L, 100L, "DRAFT");
        when(versions.selectById(1001L)).thenReturn(version);
        when(versions.update(any(), any())).thenReturn(1);
        assertThat(service.publish(1001L).state()).isEqualTo("PUBLISHED");

        when(bindings.selectOne(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(null);
        var binding = service.activate(new ConfigGovernanceService.ActivateConfig(100L, 1001L, "TENANT", null));
        assertThat(binding.currentVersionId()).isEqualTo(1001L);
    }

    @Test
    void rejectsPublishingNonDraftAndInvalidActivationTargets() {
        when(versions.selectById(1001L)).thenReturn(version(1001L, 100L, "PUBLISHED"));
        assertThatThrownBy(() -> service.publish(1001L)).hasMessageContaining("FND-CFG-007");
        assertThatThrownBy(() -> service.activate(
            new ConfigGovernanceService.ActivateConfig(100L, 1001L, "TENANT", 10L)))
            .hasMessageContaining("FND-CFG-015");
        assertThatThrownBy(() -> service.activate(
            new ConfigGovernanceService.ActivateConfig(100L, 1001L, "STORE", null)))
            .hasMessageContaining("FND-CFG-016");
    }

    @Test
    void rollsBackBySwappingPublishedCurrentAndPreviousVersions() {
        ConfigBindingEntity binding = new ConfigBindingEntity();
        binding.setBindingId(500L);
        binding.setTemplateId(100L);
        binding.setTargetType("TENANT");
        binding.setCurrentVersionId(1002L);
        binding.setPreviousVersionId(1001L);
        binding.setVersion(2);
        when(bindings.selectById(500L)).thenReturn(binding);
        when(bindings.updateById(any(ConfigBindingEntity.class))).thenReturn(1);
        when(versions.selectById(1001L)).thenReturn(version(1001L, 100L, "PUBLISHED"));

        var rolledBack = service.rollback(500L);

        assertThat(rolledBack.currentVersionId()).isEqualTo(1001L);
        assertThat(rolledBack.previousVersionId()).isEqualTo(1002L);
    }

    @Test
    void listsTemplatesAndAllocatesTheNextLockedVersionNumber() {
        when(templates.selectList(any())).thenReturn(List.of(template(100L, "ACTIVE")));
        assertThat(service.listTemplates()).extracting("templateId").containsExactly(100L);

        when(templates.selectById(100L)).thenReturn(template(100L, "ACTIVE"));
        when(versions.selectLatestForUpdate("TENANT_A", 100L))
            .thenReturn(version(1000L, 100L, "PUBLISHED"));
        assertThat(service.createVersion(100L,
            new ConfigGovernanceService.CreateVersion("1.1", Map.of("enabled", true))).versionNo())
            .isEqualTo(2);
    }

    @Test
    void rejectsInactiveMissingAndConcurrentConfigVersionOperations() {
        when(templates.selectById(100L)).thenReturn(template(100L, "INACTIVE"));
        assertThatThrownBy(() -> service.createVersion(100L,
            new ConfigGovernanceService.CreateVersion("1.0", Map.of())))
            .hasMessageContaining("FND-CFG-006");

        when(templates.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.createVersion(999L,
            new ConfigGovernanceService.CreateVersion("1.0", Map.of())))
            .hasMessageContaining("FND-CFG-017");

        when(versions.selectById(1001L)).thenReturn(version(1001L, 100L, "DRAFT"));
        when(versions.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.publish(1001L)).hasMessageContaining("FND-CFG-008");

        when(versions.selectById(9999L)).thenReturn(null);
        assertThatThrownBy(() -> service.publish(9999L)).hasMessageContaining("FND-CFG-018");
    }

    @Test
    void updatesExistingBindingAndRejectsInvalidPublishedVersionPair() {
        ConfigTemplateVersionEntity published = version(1002L, 100L, "PUBLISHED");
        when(versions.selectById(1002L)).thenReturn(published);
        ConfigBindingEntity binding = new ConfigBindingEntity();
        binding.setBindingId(500L);
        binding.setTemplateId(100L);
        binding.setTargetType("TENANT");
        binding.setCurrentVersionId(1001L);
        binding.setVersion(0);
        when(bindings.selectOne(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(binding);
        when(bindings.updateById(any(ConfigBindingEntity.class))).thenReturn(1);

        var updated = service.activate(new ConfigGovernanceService.ActivateConfig(100L, 1002L, "TENANT", null));
        assertThat(updated.currentVersionId()).isEqualTo(1002L);
        assertThat(updated.previousVersionId()).isEqualTo(1001L);

        binding.setCurrentVersionId(1002L);
        var unchanged = service.activate(new ConfigGovernanceService.ActivateConfig(100L, 1002L, "TENANT", null));
        assertThat(unchanged.currentVersionId()).isEqualTo(1002L);

        when(versions.selectById(1003L)).thenReturn(version(1003L, 200L, "PUBLISHED"));
        assertThatThrownBy(() -> service.activate(
            new ConfigGovernanceService.ActivateConfig(100L, 1003L, "TENANT", null)))
            .hasMessageContaining("FND-CFG-010");

        when(versions.selectById(1004L)).thenReturn(version(1004L, 100L, "DRAFT"));
        assertThatThrownBy(() -> service.activate(
            new ConfigGovernanceService.ActivateConfig(100L, 1004L, "TENANT", null)))
            .hasMessageContaining("FND-CFG-010");
    }

    @Test
    void rejectsUnsafeRollbackStatesAndOptimisticConflict() {
        when(bindings.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.rollback(999L)).hasMessageContaining("FND-CFG-012");

        ConfigBindingEntity binding = new ConfigBindingEntity();
        binding.setBindingId(500L);
        binding.setTemplateId(100L);
        binding.setTargetType("TENANT");
        binding.setCurrentVersionId(1002L);
        binding.setVersion(0);
        when(bindings.selectById(500L)).thenReturn(binding);
        assertThatThrownBy(() -> service.rollback(500L)).hasMessageContaining("FND-CFG-013");

        binding.setPreviousVersionId(1001L);
        when(versions.selectById(1001L)).thenReturn(version(1001L, 200L, "PUBLISHED"));
        assertThatThrownBy(() -> service.rollback(500L)).hasMessageContaining("FND-CFG-014");

        when(versions.selectById(1001L)).thenReturn(version(1001L, 100L, "PUBLISHED"));
        when(bindings.updateById(any(ConfigBindingEntity.class))).thenReturn(0);
        assertThatThrownBy(() -> service.rollback(500L)).hasMessageContaining("FND-CFG-011");
    }

    private ConfigTemplateEntity template(long id, String status) {
        ConfigTemplateEntity entity = new ConfigTemplateEntity();
        entity.setTemplateId(id);
        entity.setTemplateCode("CONVENIENCE");
        entity.setTemplateName("Synthetic");
        entity.setIndustry("CONVENIENCE");
        entity.setStatus(status);
        entity.setVersion(0);
        return entity;
    }

    private ConfigTemplateVersionEntity version(long id, long templateId, String state) {
        ConfigTemplateVersionEntity entity = new ConfigTemplateVersionEntity();
        entity.setConfigVersionId(id);
        entity.setTemplateId(templateId);
        entity.setVersionNo(1);
        entity.setSchemaVersion("1.0");
        entity.setContentSha256("a".repeat(64));
        entity.setState(state);
        return entity;
    }
}
