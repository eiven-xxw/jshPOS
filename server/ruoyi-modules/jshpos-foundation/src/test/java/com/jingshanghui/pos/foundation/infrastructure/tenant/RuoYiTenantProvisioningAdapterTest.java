package com.jingshanghui.pos.foundation.infrastructure.tenant;

import com.jingshanghui.pos.foundation.application.port.TenantProvisioningPort;
import org.dromara.system.domain.bo.SysTenantBo;
import org.dromara.system.domain.vo.SysTenantVo;
import org.dromara.system.service.ISysTenantService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 技术租户适配器只负责映射，并在调用后清除一次性字符密码。 */
class RuoYiTenantProvisioningAdapterTest {
    @Test void provisionsDisabledTenantAndClearsOneTimeSecret() {
        ISysTenantService service=mock(ISysTenantService.class); char[] secret="Synthetic-Pass-01".toCharArray();
        when(service.insertByBo(any())).thenAnswer(invocation->{SysTenantBo bo=invocation.getArgument(0);bo.setId(91L);assertThat(bo.getStatus()).isEqualTo("1");return true;});
        SysTenantVo vo=new SysTenantVo();vo.setId(91L);vo.setTenantId("200001");when(service.queryById(91L)).thenReturn(vo);
        var result=new RuoYiTenantProvisioningAdapter(service).provision(new TenantProvisioningPort.ProvisionTenant("虚构商户","虚构联系人","00000000000","synthetic",secret,1L,50));
        assertThat(result.tenantId()).isEqualTo("200001");assertThat(secret).containsOnly('\0');
    }
    @Test void changesStatusThroughSystemOwner() {ISysTenantService service=mock(ISysTenantService.class);SysTenantVo vo=new SysTenantVo();vo.setId(91L);vo.setTenantId("200001");when(service.queryByTenantId("200001")).thenReturn(vo);when(service.updateTenantStatus(any())).thenReturn(1);var adapter=new RuoYiTenantProvisioningAdapter(service);adapter.changeStatus("200001", TenantProvisioningPort.TechnicalTenantStatus.ACTIVE);verify(service).updateTenantStatus(argThat(v->"0".equals(v.getStatus())));}
    @Test void missingTechnicalTenantFailsClosed(){ISysTenantService service=mock(ISysTenantService.class);assertThatThrownBy(()->new RuoYiTenantProvisioningAdapter(service).changeStatus("missing", TenantProvisioningPort.TechnicalTenantStatus.DISABLED)).hasMessageContaining("SAA-FND-003");}
}
