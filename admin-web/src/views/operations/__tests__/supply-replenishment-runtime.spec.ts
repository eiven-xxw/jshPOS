// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => {
  let sequence = 0;
  const mock = () => vi.fn();
  return {
    reset: () => (sequence = 0),
    changeSupplierState: mock(),
    confirmProcurementReceipt: mock(),
    createProcurementOrder: mock(),
    createProcurementReceipt: mock(),
    createProcurementReturn: mock(),
    createReplenishmentPolicy: mock(),
    createReplenishmentPurchaseDraft: mock(),
    createSupplier: mock(),
    createTransfer: mock(),
    dispatchTransfer: mock(),
    generateReplenishmentSuggestions: mock(),
    getProcurementOrder: mock(),
    getProcurementReceipt: mock(),
    getTransfer: mock(),
    listReplenishmentPolicies: mock(),
    listReplenishmentSuggestions: mock(),
    newOperationCommandId: vi.fn(() => `01J${String(++sequence).padStart(23, '0')}`),
    receiveTransfer: mock(),
    transitionProcurementOrder: mock(),
    transitionProcurementReturn: mock(),
    transitionReplenishmentPolicy: mock(),
    transitionReplenishmentSuggestion: mock(),
    transitionTransfer: mock()
  };
});
vi.mock('@/api/operations', () => api);

import ReplenishmentPanel from '../components/ReplenishmentPanel.vue';
import SupplyPanel from '../components/SupplyPanel.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R3 VUE-11/VUE-10 供应链与补货', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.reset();
  });

  it('保持 Advanced→Supply→Replenishment 组合并为两页挂载独立状态面', () => {
    const wrapper = mount(SupplyPanel, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    expect(wrapper.findComponent(ReplenishmentPanel).exists()).toBe(true);
    expect(wrapper.find('[data-testid="vue-11-state"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="vue-10-state"]').exists()).toBe(true);
  });

  it('补货读取失败显示安全错误且不会伪造成空建议', async () => {
    api.listReplenishmentPolicies.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'REPLENISHMENT_SCOPE_DENIED', msg: '无权访问该门店补货规则' },
        headers: { 'x-correlation-id': 'corr-vue10-denied' }
      }
    });
    const wrapper = mount(SupplyPanel, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    await wrapper.find('[data-testid="replenishment-policy-read"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="vue-10-error"]').text()).toContain('REPLENISHMENT_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-10-error"]').text()).toContain('corr-vue10-denied');
  });
});
