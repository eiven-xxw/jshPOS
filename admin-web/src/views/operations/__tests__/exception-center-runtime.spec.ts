// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  claimExceptionCase: vi.fn(),
  closeExceptionCase: vi.fn(),
  executeExceptionRepair: vi.fn(),
  getExceptionCase: vi.fn(),
  listExceptionCases: vi.fn(),
  planExceptionRepair: vi.fn(),
  reopenExceptionCase: vi.fn(),
  reviewExceptionCase: vi.fn(),
  scanExceptionOwners: vi.fn(),
  startExceptionCase: vi.fn(),
  transferExceptionCase: vi.fn()
}));
vi.mock('@/api/exception-center', () => api);
vi.mock('@/api/operations', () => ({ newOperationCommandId: vi.fn(() => '01J00000000000000000000001') }));

import ExceptionCenterPage from '../exception-center/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R8 VUE-13 异常中心页面', () => {
  beforeEach(() => vi.clearAllMocks());

  it('异常列表失败展示安全错误与关联标识', async () => {
    api.listExceptionCases.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'EXCEPTION_SCOPE_DENIED', msg: '无权访问该门店异常' },
        headers: { 'x-correlation-id': 'corr-vue13-denied' }
      }
    });
    const wrapper = mount(ExceptionCenterPage, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    await wrapper.findAll('input')[0].setValue('1101');
    await wrapper.find('[data-testid="exception-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-13-error"]').text()).toContain('EXCEPTION_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-13-error"]').text()).toContain('corr-vue13-denied');
  });

  it('Owner扫描结果未知时复用原操作身份并禁止生成第二个命令', async () => {
    api.scanExceptionOwners.mockRejectedValue(
      Object.assign(new Error('unsafe-body'), {
        isAxiosError: true,
        response: {
          status: 503,
          data: { code: 'EXCEPTION_SCAN_UNKNOWN', msg: '扫描结果未知' },
          headers: { 'x-correlation-id': 'corr-vue13-unknown' }
        }
      })
    );
    const wrapper = mount(ExceptionCenterPage, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    await wrapper.find('[data-testid="exception-store"] input').setValue('1101');
    await wrapper.find('[data-testid="exception-scan"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="exception-scan"]').trigger('click');
    await flushPromises();

    expect(api.scanExceptionOwners).toHaveBeenCalledTimes(1);
    expect(wrapper.find('[data-testid="vue-13-error"]').text()).toContain('EXCEPTION_SCAN_UNKNOWN');
    expect(wrapper.find('[data-testid="vue-13-error"]').text()).toContain('corr-vue13-unknown');
  });
});
