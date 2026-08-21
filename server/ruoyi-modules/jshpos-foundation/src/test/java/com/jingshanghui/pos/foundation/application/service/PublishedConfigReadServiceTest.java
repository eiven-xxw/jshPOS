package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigBindingEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigBindingMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateVersionMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 已发布配置只读端口的可信租户、门店优先级和失败关闭测试。 */
class PublishedConfigReadServiceTest {

    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final ConfigTemplateMapper templates = mock(ConfigTemplateMapper.class);
    private final ConfigBindingMapper bindings = mock(ConfigBindingMapper.class);
    private final ConfigTemplateVersionMapper versions = mock(ConfigTemplateVersionMapper.class);
    private final PublishedConfigReadService service = new PublishedConfigReadService(
        context, authorization, templates, bindings, versions);

    @BeforeEach
    void setUp() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 7L, 8L, "synthetic"));
    }

    @Test
    void returnsStoreOverrideAndTrustedTenantSnapshot() {
        ConfigTemplateEntity template = template();
        when(templates.selectOne(any(Wrapper.class))).thenReturn(template);
        ConfigBindingEntity tenant = binding(10L, "TENANT", null, 100L);
        ConfigBindingEntity store = binding(11L, "STORE", 1101L, 101L);
        when(bindings.selectList(any(Wrapper.class))).thenReturn(List.of(tenant, store));
        ConfigTemplateVersionEntity version = version(101L, 1L, "PUBLISHED");
        when(versions.selectById(101L)).thenReturn(version);

        var result = service.find("PROMOTION_MANUAL_AUTHORITY", 1101L).orElseThrow();

        assertThat(result.tenantId()).isEqualTo("TENANT_A");
        assertThat(result.configVersionId()).isEqualTo(101L);
        assertThat(result.contentSha256()).hasSize(64);
        verify(authorization).requireStoreAccess(1101L);
    }

    @Test
    void returnsEmptyForMissingTemplateOrBinding() {
        when(templates.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(service.find("SHIFT_CASH_DIFFERENCE", 1101L)).isEmpty();

        when(templates.selectOne(any(Wrapper.class))).thenReturn(template());
        when(bindings.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(service.find("SHIFT_CASH_DIFFERENCE", 1101L)).isEmpty();
    }

    @Test
    void rejectsInvalidReadCondition() {
        assertThatThrownBy(() -> service.find("bad", 1101L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("FND-CFG-020");
        assertThatThrownBy(() -> service.find("VALID_CODE", null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("FND-CFG-020");
    }

    @Test
    void rejectsBindingToMissingOrUnpublishedVersion() {
        when(templates.selectOne(any(Wrapper.class))).thenReturn(template());
        when(bindings.selectList(any(Wrapper.class))).thenReturn(List.of(binding(11L, "STORE", 1101L, 101L)));
        when(versions.selectById(101L)).thenReturn(null);
        assertThatThrownBy(() -> service.find("SHIFT_CASH_DIFFERENCE", 1101L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("FND-CFG-021");

        when(versions.selectById(101L)).thenReturn(version(101L, 99L, "DRAFT"));
        assertThatThrownBy(() -> service.find("SHIFT_CASH_DIFFERENCE", 1101L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("FND-CFG-021");
    }

    private ConfigTemplateEntity template() {
        ConfigTemplateEntity value = new ConfigTemplateEntity();
        value.setTemplateId(1L);
        value.setTemplateCode("PROMOTION_MANUAL_AUTHORITY");
        value.setStatus("ACTIVE");
        return value;
    }

    private ConfigBindingEntity binding(Long id, String targetType, Long targetId, Long versionId) {
        ConfigBindingEntity value = new ConfigBindingEntity();
        value.setBindingId(id);
        value.setTemplateId(1L);
        value.setTargetType(targetType);
        value.setTargetId(targetId);
        value.setCurrentVersionId(versionId);
        return value;
    }

    private ConfigTemplateVersionEntity version(Long id, Long templateId, String state) {
        ConfigTemplateVersionEntity value = new ConfigTemplateVersionEntity();
        value.setConfigVersionId(id);
        value.setTemplateId(templateId);
        value.setVersionNo(1);
        value.setState(state);
        value.setContentJson("{\"value\":1}");
        value.setContentSha256("a".repeat(64));
        return value;
    }
}
