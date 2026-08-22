package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigBindingEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigBindingMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateVersionMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreIndustryReadServiceTest {
    private final ConfigBindingMapper bindings = mock(ConfigBindingMapper.class);
    private final ConfigTemplateMapper templates = mock(ConfigTemplateMapper.class);
    private final ConfigTemplateVersionMapper versions = mock(ConfigTemplateVersionMapper.class);
    private final StoreMapper stores = mock(StoreMapper.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final TrustedTenantContext tenant = mock(TrustedTenantContext.class);
    private final StoreIndustryReadService service = new StoreIndustryReadService(
        bindings, templates, versions, stores, authorization, tenant);

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "industry-test"),
            ConfigBindingEntity.class);
    }

    @Test
    void returnsPublishedTenantBoundIndustryAndBusinessClock() {
        Entities entities = validEntities();
        stub(entities);

        var result = service.requireCurrentIndustry(1101L);

        assertThat(result.storeId()).isEqualTo(1101L);
        assertThat(result.templateId()).isEqualTo(20L);
        assertThat(result.templateVersionId()).isEqualTo(30L);
        assertThat(result.versionNo()).isEqualTo(3);
        assertThat(result.industry()).isEqualTo("COMMUNITY_SUPERMARKET");
        assertThat(result.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(result.businessDayStart()).isEqualTo(LocalTime.of(4, 0));
        verify(authorization).requireStoreAccess(1101L);
    }

    @Test
    void rejectsInvalidStoreAndMissingBinding() {
        assertThatThrownBy(() -> service.requireCurrentIndustry(null)).hasMessageContaining("FND-IND-001");
        assertThatThrownBy(() -> service.requireCurrentIndustry(0L)).hasMessageContaining("FND-IND-001");
        when(bindings.selectOne(any(), eq(false))).thenReturn(null);
        assertThatThrownBy(() -> service.requireCurrentIndustry(1101L)).hasMessageContaining("FND-IND-002");

        ConfigBindingEntity binding = validEntities().binding();
        binding.setCurrentVersionId(null);
        when(bindings.selectOne(any(), eq(false))).thenReturn(binding);
        assertThatThrownBy(() -> service.requireCurrentIndustry(1101L)).hasMessageContaining("FND-IND-002");
    }

    @Test
    void rejectsEveryBrokenPublishedIdentityAndTenantBoundary() {
        for (int fault = 0; fault < 12; fault++) {
            Entities entities = validEntities();
            switch (fault) {
                case 0 -> entities = new Entities(entities.binding(), null, entities.template(), entities.store());
                case 1 -> entities = new Entities(entities.binding(), entities.version(), null, entities.store());
                case 2 -> entities.version().setState("DRAFT");
                case 3 -> entities.template().setStatus("INACTIVE");
                case 4 -> entities.version().setTemplateId(99L);
                case 5 -> entities = new Entities(entities.binding(), entities.version(), entities.template(), null);
                case 6 -> entities.binding().setTenantId("TENANT_B");
                case 7 -> entities.version().setTenantId("TENANT_B");
                case 8 -> entities.template().setTenantId("TENANT_B");
                case 9 -> entities.store().setTenantId("TENANT_B");
                case 10 -> entities.store().setZoneId(null);
                case 11 -> entities.store().setBusinessDayStart(null);
                default -> throw new IllegalStateException("unexpected fault");
            }
            stub(entities);
            assertThatThrownBy(() -> service.requireCurrentIndustry(1101L))
                .as("fault %s", fault)
                .hasMessageContaining("FND-IND-003");
        }
    }

    private void stub(Entities entities) {
        when(tenant.requireTenantId()).thenReturn("TENANT_A");
        when(bindings.selectOne(any(), eq(false))).thenReturn(entities.binding());
        when(versions.selectById(30L)).thenReturn(entities.version());
        when(templates.selectById(20L)).thenReturn(entities.template());
        when(stores.selectById(1101L)).thenReturn(entities.store());
    }

    private static Entities validEntities() {
        ConfigBindingEntity binding = new ConfigBindingEntity();
        binding.setTenantId("TENANT_A");
        binding.setTemplateId(20L);
        binding.setTargetType("STORE");
        binding.setTargetId(1101L);
        binding.setCurrentVersionId(30L);

        ConfigTemplateVersionEntity version = new ConfigTemplateVersionEntity();
        version.setTenantId("TENANT_A");
        version.setConfigVersionId(30L);
        version.setTemplateId(20L);
        version.setVersionNo(3);
        version.setState("PUBLISHED");
        version.setContentSha256("a".repeat(64));

        ConfigTemplateEntity template = new ConfigTemplateEntity();
        template.setTenantId("TENANT_A");
        template.setTemplateId(20L);
        template.setIndustry("COMMUNITY_SUPERMARKET");
        template.setStatus("ACTIVE");

        StoreEntity store = new StoreEntity();
        store.setTenantId("TENANT_A");
        store.setStoreId(1101L);
        store.setZoneId("Asia/Shanghai");
        store.setBusinessDayStart(LocalTime.of(4, 0));
        return new Entities(binding, version, template, store);
    }

    private record Entities(ConfigBindingEntity binding, ConfigTemplateVersionEntity version,
                            ConfigTemplateEntity template, StoreEntity store) {
    }
}
