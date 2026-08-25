// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const serviceApi = vi.hoisted(() => ({
  cleanupServiceAttachment: vi.fn(),
  commandServiceProject: vi.fn(),
  commandServiceTicket: vi.fn(),
  completeServiceProjectCheck: vi.fn(),
  createServiceCatalog: vi.fn(),
  createServiceProject: vi.fn(),
  createServiceTicket: vi.fn(),
  getServiceProject: vi.fn(),
  getServiceTicket: vi.fn(),
  issueServiceAttachmentDownload: vi.fn(),
  listServiceProjects: vi.fn(),
  listServiceTickets: vi.fn(),
  publishServiceCatalog: vi.fn(),
  uploadServiceAttachment: vi.fn()
}));
vi.mock('@/api/service', () => serviceApi);

import ServiceOperations from '../../service/operations/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};
const mountPage = () =>
  mount(ServiceOperations, {
    attachTo: document.body,
    global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { Teleport: true, teleport: true, transition: false } }
  });

describe('G9A-R3C R2 VUE-18 服务运营页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
  });

  it('按服务端可信门店范围读取，空结果显示具名空态', async () => {
    serviceApi.listServiceProjects.mockResolvedValue({ data: [] });
    const wrapper = mountPage();
    await wrapper.get('[data-testid="service-project-refresh"]').trigger('click');
    await flushPromises();

    expect(serviceApi.listServiceProjects).toHaveBeenCalledWith(1001);
    expect(wrapper.find('[data-testid="service-empty"]').exists()).toBe(true);
    expect(wrapper.get('[data-testid="service-project-refresh"]').attributes('data-permission')).toBe('service:project:read');
  });

  it('读取失败仅显示稳定错误和关联标识，不把响应正文当作页面内容', async () => {
    serviceApi.listServiceProjects.mockRejectedValue(
      Object.assign(new Error('seed-service-read'), {
        response: { data: { code: 'SERVICE_SCOPE_DENIED', msg: '门店范围拒绝' }, headers: { 'x-correlation-id': 'corr-service-r3c-01' } }
      })
    );
    const wrapper = mountPage();
    await wrapper.get('[data-testid="service-project-refresh"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="service-error"]').text()).toContain('SERVICE_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="service-error"]').text()).toContain('corr-service-r3c-01');
    expect(wrapper.find('[data-testid="service-retry"]').exists()).toBe(true);
  });
});
