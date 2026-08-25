// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const saasApi = vi.hoisted(() => ({
  activateSaasApplication: vi.fn(),
  advanceEntitlementVersion: vi.fn(),
  approveSaasApplication: vi.fn(),
  changeTenantLifecycle: vi.fn(),
  createEntitlementVersion: vi.fn(),
  createSaasApplication: vi.fn(),
  createSaasPlan: vi.fn(),
  getSaasApplication: vi.fn(),
  initializeSaasApplication: vi.fn(),
  preflightSaasApplication: vi.fn(),
  provisionSaasApplication: vi.fn()
}));
vi.mock('@/api/saas', () => saasApi);

import SaasOperations from '../../saas/operations/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3C R4 VUE-17 SaaS 运营页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
  });

  it('读取越权失败显示稳定错误、关联标识和只读恢复入口', async () => {
    saasApi.getSaasApplication.mockRejectedValue(
      Object.assign(new Error('seed-saas-scope'), {
        response: { data: { code: 'SAA_TENANT_SCOPE_DENIED', msg: '申请不在平台授权范围' }, headers: { 'x-correlation-id': 'corr-saas-r3c-01' } }
      })
    );
    const wrapper = mount(SaasOperations, {
      attachTo: document.body,
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { Teleport: true, teleport: true, transition: false } }
    });
    await wrapper.get('[data-testid="saas-application-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="saas-error"]').text()).toContain('SAA_TENANT_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="saas-error"]').text()).toContain('corr-saas-r3c-01');
    expect(wrapper.get('[data-testid="saas-application-read"]').attributes('data-permission')).toBe('saas:application:read');
    expect(wrapper.find('[data-testid="saas-retry"]').exists()).toBe(true);
  });

  it('生命周期入口始终绑定服务端最小权限', () => {
    const wrapper = mount(SaasOperations, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { Teleport: true, teleport: true, transition: false } }
    });
    const terminate = wrapper.get('[data-testid="saas-lifecycle-terminate-logical"]');
    expect(terminate.attributes('data-permission')).toBe('saas:tenant:lifecycle');
  });
});
