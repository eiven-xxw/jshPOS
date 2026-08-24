// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  approveDailyClose: vi.fn(),
  createDailyClose: vi.fn(),
  detectDailyCloseLateFacts: vi.fn(),
  getDailyClose: vi.fn(),
  listDailyCloses: vi.fn(),
  preflightDailyClose: vi.fn(),
  signDailyClose: vi.fn()
}));
vi.mock('@/api/daily-close', () => api);
vi.mock('@/api/operations', () => ({ newOperationCommandId: vi.fn(() => '01J00000000000000000000001') }));

import DailyClosePage from '../daily-close/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R7 VUE-12 门店日结页面', () => {
  beforeEach(() => vi.clearAllMocks());

  it('日结列表失败显示安全错误且不伪造渠道对账通过', async () => {
    api.listDailyCloses.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'DAILY_CLOSE_SCOPE_DENIED', msg: '无权访问该门店日结' },
        headers: { 'x-correlation-id': 'corr-vue12-denied' }
      }
    });
    const wrapper = mount(DailyClosePage, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    await wrapper.findAll('input')[0].setValue('1101');
    await wrapper.find('[data-testid="daily-close-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-12-error"]').text()).toContain('DAILY_CLOSE_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-12-error"]').text()).toContain('corr-vue12-denied');
    expect(wrapper.text()).toContain('不会伪造渠道对账通过');
  });
});
