// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import OwnerPageFeedback from '../components/OwnerPageFeedback.vue';

describe('G9A-R3B R0 Owner 页面反馈组件', () => {
  it('区分加载、空态和未知结果，并只显示安全错误信封', async () => {
    const wrapper = mount(OwnerPageFeedback, {
      props: { surfaceId: 'VUE-08', state: 'LOADING' },
      global: { plugins: [ElementPlus] }
    });
    expect(wrapper.find('[data-testid="vue-08-loading"]').exists()).toBe(true);

    await wrapper.setProps({ state: 'EMPTY' });
    expect(wrapper.find('[data-testid="vue-08-empty"]').text()).toContain('暂无数据');

    await wrapper.setProps({
      state: 'UNKNOWN',
      failure: {
        code: 'MEMBER_PRICE_UNKNOWN',
        message: '结果未知，请查询原命令',
        correlationId: 'corr-r3b-feedback-01',
        operationIdentity: '01J00000000000000000000001'
      }
    });
    expect(wrapper.find('[data-testid="vue-08-error"]').text()).toContain('MEMBER_PRICE_UNKNOWN');
    expect(wrapper.find('[data-testid="vue-08-error"]').text()).toContain('corr-r3b-feedback-01');
    expect(wrapper.find('[data-testid="vue-08-error"]').text()).not.toContain('stack');
    await wrapper.find('[data-testid="vue-08-retry"]').trigger('click');
    expect(wrapper.emitted('retry')).toHaveLength(1);
  });
});
