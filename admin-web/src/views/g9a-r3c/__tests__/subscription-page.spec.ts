// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const subscriptionApi = vi.hoisted(() => ({
  activateSubscription: vi.fn(),
  createSubscription: vi.fn(),
  getSubscription: vi.fn(),
  renewSubscription: vi.fn(),
  requestSubscriptionTermination: vi.fn(),
  restoreSubscription: vi.fn(),
  suspendSubscription: vi.fn(),
  terminateSubscription: vi.fn()
}));
vi.mock('@/api/subscription', () => subscriptionApi);

import SubscriptionOperations from '../../subscription/operations/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};
const mountPage = () =>
  mount(SubscriptionOperations, {
    attachTo: document.body,
    global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { Teleport: true, teleport: true, transition: false } }
  });

const detail = {
  subscription: {
    subscriptionId: 'SUB-R3C-001',
    tenantId: 'TENANT_A',
    state: 'DRAFT',
    currentTermVersion: 1,
    contentSha256: 'a'.repeat(64)
  },
  accessMode: 'NORMAL',
  retainedCapabilities: [],
  terms: []
};

describe('G9A-R3C R5 VUE-19 订阅运营页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
  });

  it('读取失败显示安全错误和只读恢复入口', async () => {
    subscriptionApi.getSubscription.mockRejectedValue(
      Object.assign(new Error('seed-subscription-read'), {
        response: { data: { code: 'SUBSCRIPTION_SCOPE_DENIED', msg: '订阅不在可信租户范围' }, headers: { 'x-correlation-id': 'corr-sub-r3c-01' } }
      })
    );
    const wrapper = mountPage();
    await wrapper.get('[data-testid="subscription-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="subscription-error"]').text()).toContain('SUBSCRIPTION_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="subscription-error"]').text()).toContain('corr-sub-r3c-01');
    expect(wrapper.get('[data-testid="subscription-read"]').attributes('data-permission')).toBe('subscription:read');
  });

  it('创建失败恢复复用原命令身份，服务端成功后才释放', async () => {
    subscriptionApi.createSubscription
      .mockRejectedValueOnce(
        Object.assign(new Error('seed-subscription-conflict'), { response: { status: 409, data: { code: 'SUBSCRIPTION_CONFLICT' } } })
      )
      .mockResolvedValueOnce({ data: detail });
    const wrapper = mountPage();
    await wrapper.get('[data-testid="subscription-create"]').trigger('click');
    await flushPromises();
    const firstIdentity = subscriptionApi.createSubscription.mock.calls[0][2];

    await wrapper.get('[data-testid="subscription-create"]').trigger('click');
    await flushPromises();
    expect(subscriptionApi.createSubscription.mock.calls[1][2]).toEqual(firstIdentity);
    expect(wrapper.text()).toContain('DRAFT');
  });
});
