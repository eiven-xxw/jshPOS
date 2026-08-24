// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({ getEffectiveLotPolicy: vi.fn(), listLotExpiryAlerts: vi.fn(), publishLotPolicy: vi.fn() }));
vi.mock('@/api/lot-expiry', () => api);
vi.mock('@/api/operations', () => ({ newOperationCommandId: vi.fn(() => '01J00000000000000000000001') }));

import LotExpiryPage from '../lot-expiry/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R9 VUE-14 批次效期页面', () => {
  beforeEach(() => vi.clearAllMocks());

  it('批次策略读取失败展示安全错误且保持社区超市模板边界', async () => {
    api.getEffectiveLotPolicy.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'LOT_POLICY_SCOPE_DENIED', msg: '无权访问该门店批次策略' },
        headers: { 'x-correlation-id': 'corr-vue14-denied' }
      }
    });
    const wrapper = mount(LotExpiryPage, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    const inputs = wrapper.findAll('input');
    await inputs[0].setValue('1101');
    await inputs[1].setValue('101');
    await wrapper.find('[data-testid="lot-policy-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-14-error"]').text()).toContain('LOT_POLICY_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-14-error"]').text()).toContain('corr-vue14-denied');
    expect(wrapper.text()).toContain('仅适用于社区超市模板');
  });
});
