// @vitest-environment happy-dom
import ElementPlus, { ElMessageBox } from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const terminalApi = vi.hoisted(() => ({
  changeTerminalStatus: vi.fn(),
  issueTerminalActivation: vi.fn(),
  listTerminals: vi.fn(),
  rotateTerminalCredential: vi.fn()
}));
vi.mock('@/api/terminal', () => terminalApi);

import TerminalRegistry from '../../terminal/registry/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};
const mountPage = () =>
  mount(TerminalRegistry, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      directives: { hasPermi },
      stubs: {
        Teleport: true,
        teleport: true,
        transition: false,
        Pagination: true,
        ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue"><slot /><slot name="footer" /></section>' }
      }
    }
  });

describe('G9A-R3C R3 VUE-20 终端登记页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    terminalApi.listTerminals.mockResolvedValue({ data: { items: [], total: 0, page: 1, size: 50 } });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('挂载即从服务端可信范围读取，空态与最小权限可见', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="terminal-empty"]').exists()).toBe(true);
    expect(wrapper.get('[data-testid="terminal-query"]').attributes('data-permission')).toBe('terminal:registry:read');
    expect(wrapper.get('[data-testid="terminal-issue-open"]').attributes('data-permission')).toBe('terminal:activation:issue');
  });

  it('签发失败重试复用原键，一次性秘密关闭后立即清空', async () => {
    terminalApi.issueTerminalActivation
      .mockRejectedValueOnce(Object.assign(new Error('seed-issue-conflict'), { response: { status: 409, data: { code: 'TERMINAL_ISSUE_CONFLICT' } } }))
      .mockResolvedValueOnce({
        data: { activationId: 'ACT-R3C-001', activationSecret: 'one-time-r3c-secret', expiresAt: '2026-08-25T11:00:00Z', status: 'ISSUED' }
      });
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.get('[data-testid="terminal-issue-open"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-testid="terminal-issue-submit"]').trigger('click');
    await flushPromises();
    const firstKey = terminalApi.issueTerminalActivation.mock.calls[0][0].idempotencyKey;

    await wrapper.get('[data-testid="terminal-issue-submit"]').trigger('click');
    await flushPromises();
    expect(terminalApi.issueTerminalActivation.mock.calls[1][0].idempotencyKey).toBe(firstKey);
    expect(wrapper.text()).toContain('one-time-r3c-secret');

    await wrapper.get('[data-testid="terminal-secret-close"]').trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('one-time-r3c-secret');
  });
});
