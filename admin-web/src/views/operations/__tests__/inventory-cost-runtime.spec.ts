// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  createStocktake: vi.fn(),
  getCostBalance: vi.fn(),
  getCostLedger: vi.fn(),
  getInventoryBalance: vi.fn(),
  getInventoryLedger: vi.fn(),
  getStocktake: vi.fn(),
  newOperationCommandId: vi.fn(() => '01J00000000000000000000001'),
  rebuildCostBalance: vi.fn(),
  rebuildInventoryBalance: vi.fn(),
  recordStocktakeCount: vi.fn(),
  transitionStocktake: vi.fn()
}));
vi.mock('@/api/operations', () => api);

import InventoryCostPanel from '../components/InventoryCostPanel.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R2 VUE-07 库存成本页面', () => {
  beforeEach(() => vi.clearAllMocks());

  it('库存读取任一 Owner 失败时保留安全错误，不被另一读取结果覆盖', async () => {
    api.getInventoryBalance.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'INVENTORY_SCOPE_DENIED', msg: '无权访问该仓库' },
        headers: { 'x-correlation-id': 'corr-vue07-denied' }
      }
    });
    api.getInventoryLedger.mockResolvedValue({ data: [] });
    const wrapper = mount(InventoryCostPanel, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    const inputs = wrapper.findAll('input');
    await inputs[0].setValue('01J00000000000000000000011');
    await inputs[1].setValue('101');
    await wrapper.find('[data-testid="inventory-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-07-error"]').text()).toContain('INVENTORY_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-07-error"]').text()).toContain('corr-vue07-denied');
    expect(api.getInventoryBalance).toHaveBeenCalledTimes(1);
    expect(api.getInventoryLedger).toHaveBeenCalledTimes(1);
  });
});
