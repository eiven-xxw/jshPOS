// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => {
  let sequence = 0;
  return {
    reset: () => (sequence = 0),
    adjustMemberPoints: vi.fn(),
    createMember: vi.fn(),
    createPrivacyRequest: vi.fn(),
    createPromotionRule: vi.fn(),
    getMemberPoints: vi.fn(),
    newOperationCommandId: vi.fn(() => `01J${String(++sequence).padStart(23, '0')}`),
    recordMemberConsent: vi.fn(),
    resolveMember: vi.fn(),
    transitionPromotionRule: vi.fn()
  };
});
vi.mock('@/api/operations', () => api);

import CustomerPromotionPanel from '../components/CustomerPromotionPanel.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R5 VUE-06 促销会员页面', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.reset();
  });

  it('会员解析失败保留安全错误且页面不展示原始响应内容', async () => {
    api.resolveMember.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'MEMBER_SCOPE_DENIED', msg: '无权访问该门店会员' },
        headers: { 'x-correlation-id': 'corr-vue06-denied' }
      }
    });
    const wrapper = mount(CustomerPromotionPanel, {
      global: {
        plugins: [ElementPlus],
        directives: { hasPermi },
        stubs: { transition: false, Teleport: true, MemberBenefitPolicyPanel: true }
      }
    });
    await wrapper.find('[data-testid="member-resolve"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-06-error"]').text()).toContain('MEMBER_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-06-error"]').text()).toContain('corr-vue06-denied');
    expect(wrapper.text()).toContain('不得在本 Sprint 输入真实手机号');
  });
});
