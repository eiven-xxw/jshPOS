// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  createRelease: vi.fn(),
  createRollout: vi.fn(),
  getRelease: vi.fn(),
  newOperationCommandId: vi.fn(() => '01J00000000000000000000001'),
  transitionRelease: vi.fn(),
  transitionRollout: vi.fn()
}));
vi.mock('@/api/operations', () => api);

import ReleasePanel from '../components/ReleasePanel.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R4 VUE-09 发布治理页面', () => {
  beforeEach(() => vi.clearAllMocks());

  it('读取失败展示安全错误并持续声明真实终端执行为零', async () => {
    api.getRelease.mockRejectedValue({
      response: {
        status: 409,
        data: { code: 'RELEASE_DIGEST_MISMATCH', msg: '发布摘要不一致' },
        headers: { 'x-correlation-id': 'corr-vue09-digest' }
      }
    });
    const wrapper = mount(ReleasePanel, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    const inputs = wrapper.findAll('input');
    await inputs[0].setValue('01J00000000000000000000001');
    await wrapper.find('[data-testid="release-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-09-error"]').text()).toContain('RELEASE_DIGEST_MISMATCH');
    expect(wrapper.text()).toContain('不发送固件、重启或真实远程命令');
  });
});
