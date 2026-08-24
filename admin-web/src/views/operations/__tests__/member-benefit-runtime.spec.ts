// @vitest-environment happy-dom
import ElementPlus, { ElMessageBox } from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const operationApi = vi.hoisted(() => {
  let sequence = 0;
  return {
    reset: () => (sequence = 0),
    newOperationCommandId: vi.fn(() => `01J${String(++sequence).padStart(23, '0')}`)
  };
});
const benefitApi = vi.hoisted(() => ({
  createBenefitPolicy: vi.fn(),
  createMemberPriceVersion: vi.fn(),
  publishMemberBenefitPackage: vi.fn(),
  transitionBenefitPolicy: vi.fn(),
  transitionMemberPrice: vi.fn()
}));

vi.mock('@/api/operations', () => ({ newOperationCommandId: operationApi.newOperationCommandId }));
vi.mock('@/api/member-benefit', () => benefitApi);

import MemberBenefitPolicyPanel from '../components/MemberBenefitPolicyPanel.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R1 VUE-08 会员权益与会员价', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    operationApi.reset();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('四个高风险按钮绑定 Controller 最小权限且挂载统一状态面', () => {
    const wrapper = mount(MemberBenefitPolicyPanel, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });

    expect(wrapper.find('[data-testid="vue-08-state"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="member-benefit-validate"]').attributes('data-permission')).toBe('member:benefit:validate');
    for (const id of ['member-price-validate', 'member-price-approve', 'member-price-publish']) {
      expect(wrapper.find(`[data-testid="${id}"]`).attributes('data-permission')).toBe('pricing:member-price:publish');
    }
  });

  it('网络未知后再次点击创建权益复用原操作且不产生第二个命令', async () => {
    benefitApi.createBenefitPolicy.mockRejectedValue(
      Object.assign(new Error('unsafe-body'), {
        isAxiosError: true,
        response: {
          status: 503,
          data: { code: 'MEMBER_BENEFIT_UNKNOWN', msg: '权益创建结果未知' },
          headers: { 'x-correlation-id': 'corr-vue08-unknown' }
        }
      })
    );
    const wrapper = mount(MemberBenefitPolicyPanel, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });

    await wrapper.find('[data-testid="member-benefit-create"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="member-benefit-create"]').trigger('click');
    await flushPromises();

    expect(benefitApi.createBenefitPolicy).toHaveBeenCalledTimes(1);
    expect(wrapper.find('[data-testid="vue-08-error"]').text()).toContain('MEMBER_BENEFIT_UNKNOWN');
    expect(wrapper.find('[data-testid="vue-08-error"]').text()).toContain('corr-vue08-unknown');
  });
});
