// @vitest-environment happy-dom
import ElementPlus, { ElMessageBox } from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const reportingApi = vi.hoisted(() => ({
  approveReportExport: vi.fn(),
  downloadReportExport: vi.fn(),
  generateReportExport: vi.fn(),
  getPaymentReconciliationAudit: vi.fn(),
  getReportExport: vi.fn(),
  issueReportDownloadToken: vi.fn(),
  queryInventoryCostDaily: vi.fn(),
  queryPaymentReconciliation: vi.fn(),
  querySalesDaily: vi.fn(),
  requestReportExport: vi.fn(),
  transitionPaymentReconciliation: vi.fn()
}));

vi.mock('@/api/reporting', () => reportingApi);
vi.mock('file-saver', () => ({ saveAs: vi.fn() }));

import ReportingOperation from '../../reporting/operation/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

const mountPage = () =>
  mount(ReportingOperation, {
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

describe('G9A-R3C R1 VUE-16 报表运营页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    reportingApi.querySalesDaily.mockResolvedValue({ data: [] });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('真实挂载后区分空态并保留查询和导出的最小权限', async () => {
    const wrapper = mountPage();
    await wrapper.get('[data-testid="reporting-query"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="reporting-empty"]').exists()).toBe(true);
    expect(wrapper.get('[data-testid="reporting-query"]').attributes('data-permission')).toBe('report:operation:read');
    expect(wrapper.get('[data-testid="reporting-export-open"]').attributes('data-permission')).toBe('report:export:request');
  });

  it('导出失败恢复复用原关联键，同一在途操作只提交一次', async () => {
    reportingApi.requestReportExport
      .mockRejectedValueOnce(Object.assign(new Error('seed-export-conflict'), { response: { status: 409, data: { code: 'EXPORT_CONFLICT' } } }))
      .mockResolvedValueOnce({
        data: { exportId: 'EXPORT-1', state: 'REQUESTED', estimatedRows: 0, approvalRequired: false, version: 1 }
    });
    const wrapper = mountPage();
    await wrapper.get('[data-testid="reporting-store"]').setValue('1001');
    await wrapper.get('[data-testid="reporting-export-open"]').trigger('click');
    await flushPromises();
    const request = document.querySelector<HTMLElement>('[data-testid="reporting-export-request"]');
    expect(request).not.toBeNull();
    request?.click();
    await flushPromises();

    expect(wrapper.find('[data-testid="reporting-error"]').text()).toContain('EXPORT_CONFLICT');
    const firstKey = reportingApi.requestReportExport.mock.calls[0][0].correlationId;

    request?.click();
    await flushPromises();
    expect(reportingApi.requestReportExport).toHaveBeenCalledTimes(2);
    expect(reportingApi.requestReportExport.mock.calls[1][0].correlationId).toBe(firstKey);
  });
});
