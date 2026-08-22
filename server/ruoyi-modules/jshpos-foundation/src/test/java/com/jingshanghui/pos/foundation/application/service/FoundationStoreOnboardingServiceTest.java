package com.jingshanghui.pos.foundation.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigBindingView;
import com.jingshanghui.pos.foundation.application.port.StoreOnboardingPort.*;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.*;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.*;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoundationStoreOnboardingServiceTest {
    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "onboarding-foundation-test");
        TableInfoHelper.initTableInfo(assistant, StoreEntity.class);
        TableInfoHelper.initTableInfo(assistant, StaffScopeEntity.class);
        TableInfoHelper.initTableInfo(assistant, ConfigBindingEntity.class);
    }

    @Mock StoreMapper stores;
    @Mock ConfigTemplateMapper templates;
    @Mock ConfigTemplateVersionMapper versions;
    @Mock ConfigBindingMapper bindings;
    @Mock StaffScopeMapper staffScopes;
    @Mock ConfigGovernanceService configs;
    @Mock ScopeAuthorizationService authorization;
    @Mock DomainAuditService audit;
    FoundationStoreOnboardingService service;
    StoreEntity target;
    StoreEntity source;
    ConfigTemplateEntity template;
    ConfigTemplateVersionEntity version;

    @BeforeEach
    void setUp() {
        service = new FoundationStoreOnboardingService(stores, templates, versions, bindings, staffScopes,
            configs, authorization, audit, new ObjectMapper());
        target = store(20L, 200L, "PREPARING", 3);
        source = store(10L, 100L, "ACTIVE", 7);
        template = new ConfigTemplateEntity();
        template.setTemplateId(30L); template.setIndustry("CONVENIENCE"); template.setStatus("ACTIVE");
        version = new ConfigTemplateVersionEntity();
        version.setConfigVersionId(40L); version.setTemplateId(30L); version.setVersionNo(5);
        version.setState("PUBLISHED"); version.setContentSha256("a".repeat(64));
        version.setContentJson("{\"business.time\":{\"zone\":\"Asia/Shanghai\"},\"ui.layout\":\"compact\"}");
        lenient().when(stores.selectById(20L)).thenReturn(target);
        lenient().when(templates.selectById(30L)).thenReturn(template);
        lenient().when(versions.selectById(40L)).thenReturn(version);
    }

    @Test
    void capturesOnlyWhitelistedTemplateDataAndSourceVersion() {
        when(stores.selectById(10L)).thenReturn(source);
        ConfigBindingEntity binding = new ConfigBindingEntity();
        binding.setCurrentVersionId(40L);
        when(bindings.selectOne(any(), eq(false))).thenReturn(binding);

        FoundationSnapshot result = service.capture(new CaptureCommand(10L, 20L, 30L, 40L));

        assertThat(result.sourceStoreVersion()).isEqualTo(7);
        assertThat(result.targetStoreVersion()).isEqualTo(3);
        assertThat(result.configItems()).containsOnlyKeys("business.time", "ui.layout");
        verify(authorization).requireTenantAdministrator();
        verify(authorization).requireStoreAccess(10L);
        verify(authorization).requireStoreAccess(20L);
    }

    @Test
    void capturesTemplateOnlyAndReportsStaffReadiness() {
        FoundationSnapshot snapshot = service.capture(new CaptureCommand(null, 20L, 30L, 40L));
        assertThat(snapshot.sourceStoreId()).isNull();
        when(staffScopes.selectCount(any())).thenReturn(2L);
        FoundationReadiness readiness = service.readiness(20L);
        assertThat(readiness.activeStaffScopeCount()).isEqualTo(2);
        assertThat(readiness.factSha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void preservesExplicitNullInOptionalWhitelistedConfig() {
        version.setContentJson("{\"device.expectation\":null,\"ui.layout\":\"compact\"}");
        FoundationSnapshot snapshot = service.capture(new CaptureCommand(null, 20L, 30L, 40L));
        assertThat(snapshot.configItems()).containsKey("device.expectation");
        assertThat(snapshot.configItems().get("device.expectation")).isNull();
        assertThatThrownBy(() -> snapshot.configItems().put("ui.layout", "changed"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void appliesOnlyMatchingFrozenSnapshot() {
        String snapshotHash = CanonicalJson.from(Map.of("business.time", Map.of("zone", "Asia/Shanghai"),
            "ui.layout", "compact")).sha256();
        when(configs.activate(any())).thenReturn(new ConfigBindingView(50L, 30L, "STORE", 20L, 40L, null, 1));
        AppliedBinding applied = service.apply(new ApplyCommand(20L, 30L, 40L, 3, snapshotHash));
        assertThat(applied.bindingId()).isEqualTo(50L);
        assertThat(applied.resultSha256()).matches("[a-f0-9]{64}");
        assertThatThrownBy(() -> service.apply(new ApplyCommand(20L, 30L, 40L, 2, snapshotHash)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("目标门店版本已漂移");
        assertThatThrownBy(() -> service.apply(new ApplyCommand(20L, 30L, 40L, 3, "b".repeat(64))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("冻结配置摘要已漂移");
    }

    @Test
    void opensPreparingStoreIdempotentlyAndAudits() {
        when(stores.update(isNull(), any())).thenReturn(1);
        OpenedStore opened = service.open(new OpenCommand(20L, 3, "完成开店检查"));
        assertThat(opened.status()).isEqualTo("ACTIVE");
        assertThat(opened.version()).isEqualTo(4);
        verify(audit).append(eq("STORE_OPENED_BY_ONBOARDING"), eq("STORE"), eq(20L), any(), any(), any());

        target.setStatus("ACTIVE"); target.setVersion(4);
        assertThat(service.open(new OpenCommand(20L, 3, "重复请求"))).isEqualTo(new OpenedStore(20L, "ACTIVE", 4));
        target.setStatus("PREPARING"); target.setVersion(3);
        when(stores.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.open(new OpenCommand(20L, 3, "并发冲突")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("并发冲突");
    }

    @Test
    void failsClosedForInvalidStoresTemplatesBindingsAndSensitiveContent() {
        assertThatThrownBy(() -> service.capture(new CaptureCommand(20L, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不能等于");
        target.setStatus("ACTIVE");
        assertThatThrownBy(() -> service.capture(new CaptureCommand(null, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PREPARING");
        target.setStatus("PREPARING");
        when(stores.selectById(10L)).thenReturn(source);
        source.setStatus("PREPARING");
        assertThatThrownBy(() -> service.capture(new CaptureCommand(10L, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ACTIVE");
        source.setStatus("ACTIVE");
        when(bindings.selectOne(any(), eq(false))).thenReturn(null);
        assertThatThrownBy(() -> service.capture(new CaptureCommand(10L, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("未绑定");

        version.setContentJson("{\"ui.layout\":{\"secret\":\"x\"}}");
        assertThatThrownBy(() -> service.capture(new CaptureCommand(null, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("禁止复制");
        version.setContentJson("{\"unknown\":1}");
        assertThatThrownBy(() -> service.capture(new CaptureCommand(null, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不在复制白名单");
        version.setContentJson("not-json");
        assertThatThrownBy(() -> service.capture(new CaptureCommand(null, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("无法安全解析");
    }

    @Test
    void rejectsMissingResourcesInvalidInputAndOpenVersion() {
        assertThatThrownBy(() -> service.capture(new CaptureCommand(null, null, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("targetStoreId");
        when(stores.selectById(20L)).thenReturn(null);
        assertThatThrownBy(() -> service.capture(new CaptureCommand(null, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不存在");
        when(stores.selectById(20L)).thenReturn(target);
        template.setStatus("INACTIVE");
        assertThatThrownBy(() -> service.capture(new CaptureCommand(null, 20L, 30L, 40L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不可用");
        template.setStatus("ACTIVE");
        assertThatThrownBy(() -> service.open(new OpenCommand(20L, 2, "版本不符")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不允许开店");
        when(stores.update(isNull(), any())).thenReturn(1);
        assertThatThrownBy(() -> service.open(new OpenCommand(20L, 3, "x")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("原因非法");
    }

    private static StoreEntity store(Long id, Long orgId, String status, int version) {
        StoreEntity value = new StoreEntity();
        value.setStoreId(id); value.setOrgUnitId(orgId); value.setStatus(status); value.setVersion(version);
        return value;
    }
}
