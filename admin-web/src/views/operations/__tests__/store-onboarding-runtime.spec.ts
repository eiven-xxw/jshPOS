// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  applyOnboardingPlan: vi.fn(),
  approveOnboardingPlan: vi.fn(),
  cancelOnboardingPlan: vi.fn(),
  checkOnboardingPlan: vi.fn(),
  createOnboardingPlan: vi.fn(),
  getOnboardingPlan: vi.fn(),
  openOnboardingStore: vi.fn(),
  preflightOnboardingPlan: vi.fn()
}));
vi.mock('@/api/onboarding', () => api);
vi.mock('@/api/operations', () => ({ newOperationCommandId: vi.fn(() => '01J00000000000000000000001') }));

import StoreOnboardingPage from '../store-onboarding/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R10 VUE-15 门店开通页面', () => {
  beforeEach(() => vi.clearAllMocks());

  it('计划读取失败展示安全错误且外部阻断保持失败关闭', async () => {
    api.getOnboardingPlan.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'ONBOARDING_SCOPE_DENIED', msg: '无权访问该开店计划' },
        headers: { 'x-correlation-id': 'corr-vue15-denied' }
      }
    });
    const wrapper = mount(StoreOnboardingPage, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    const inputs = wrapper.findAll('input');
    await inputs[4].setValue('01J00000000000000000000001');
    await wrapper.find('[data-testid="onboarding-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-15-error"]').text()).toContain('ONBOARDING_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-15-error"]').text()).toContain('corr-vue15-denied');
    expect(wrapper.text()).toContain('支付、硬件、打印和设计伙伴未解阻时只显示 BLOCKED/UNAVAILABLE');
  });
});
