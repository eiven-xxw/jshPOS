// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { mount, type VueWrapper } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const catalogApi = vi.hoisted(() => ({
  addPriceItem: vi.fn(),
  changeProductState: vi.fn(),
  createBrand: vi.fn(),
  createCategory: vi.fn(),
  createPriceBook: vi.fn(),
  createProduct: vi.fn(),
  createUnit: vi.fn(),
  listProducts: vi.fn(),
  preflightImport: vi.fn(),
  publishImport: vi.fn(),
  publishPriceBook: vi.fn(),
  rollbackImport: vi.fn(),
  confirmShelfLabelReplacement: vi.fn(),
  createShelfLabelTemplate: vi.fn(),
  dispatchShelfLabelTask: vi.fn(),
  getShelfLabelTask: vi.fn(),
  listShelfLabelTasks: vi.fn(),
  listShelfLabelTemplates: vi.fn(),
  previewShelfLabelItem: vi.fn(),
  publishShelfLabelTemplate: vi.fn(),
  recordShelfLabelException: vi.fn(),
  retireShelfLabelTemplate: vi.fn()
}));

const foundationApi = vi.hoisted(() => ({
  createConfigTemplate: vi.fn(),
  createOrgUnit: vi.fn(),
  createStore: vi.fn(),
  getBusinessDate: vi.fn(),
  listAuditEvents: vi.fn(),
  listConfigTemplates: vi.fn(),
  listOrgUnits: vi.fn(),
  listStaffScopes: vi.fn(),
  listStores: vi.fn(),
  replaceStaffScopes: vi.fn()
}));

vi.mock('@/api/catalog', () => catalogApi);
vi.mock('@/api/foundation', () => foundationApi);

import CatalogWorkbench from '../../catalog/index.vue';
import ShelfLabelPanel from '../../catalog/components/ShelfLabelPanel.vue';
import FoundationWorkbench from '../../foundation/index.vue';
import AdvancedOperations from '../../operations/advanced/index.vue';
import { useRecoverablePage } from '@/composables/useRecoverablePage';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

const mountPage = (component: Parameters<typeof mount>[0], options: Record<string, unknown> = {}): VueWrapper =>
  mount(component, {
    ...options,
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      directives: { hasPermi },
      stubs: {
        Teleport: true,
        teleport: true,
        transition: false,
        ElDrawer: { template: '<section><slot /></section>' }
      },
      ...(options.global as Record<string, unknown>)
    }
  }) as VueWrapper;

describe('G9A-R3A Vue 主路径挂载失败回归', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
  });

  it('VUE-01 区分加载、空态并为刷新绑定查询权限', async () => {
    catalogApi.listProducts.mockResolvedValue({ data: [] });
    foundationApi.listStores.mockResolvedValue({ data: [] });
    const wrapper = mountPage(CatalogWorkbench);
    await flushPromises();

    expect(wrapper.find('[data-testid="catalog-empty"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="catalog-refresh"]').attributes('data-permission')).toBe('catalog:product:query');
  });

  it('VUE-02 读取失败显示稳定错误码、关联标识和原操作恢复入口', async () => {
    catalogApi.listShelfLabelTasks.mockRejectedValue(
      Object.assign(new Error('seed-shelf-label-read'), {
        response: { data: { code: 'LBL_READ_FAILED', msg: '价签任务加载失败' }, headers: { 'x-correlation-id': 'corr-lbl-seed-01' } }
      })
    );
    const wrapper = mountPage(ShelfLabelPanel, { props: { modelValue: true, stores: [] } });
    await flushPromises();

    const error = document.querySelector<HTMLElement>('[data-testid="shelf-label-error"]');
    expect(error?.textContent).toContain('LBL_READ_FAILED');
    expect(error?.textContent).toContain('corr-lbl-seed-01');
    expect(document.querySelector('[data-testid="shelf-label-retry"]')).not.toBeNull();
  });

  it('VUE-03 任一基础资料读取失败不得伪装为空数据', async () => {
    foundationApi.listOrgUnits.mockResolvedValue({ data: [] });
    foundationApi.listStores.mockRejectedValue(
      Object.assign(new Error('seed-store-read'), {
        response: { data: { code: 'FOUNDATION_STORE_FAILED', msg: '门店读取失败' }, headers: { 'x-correlation-id': 'corr-foundation-seed-01' } }
      })
    );
    foundationApi.listConfigTemplates.mockResolvedValue({ data: [] });
    foundationApi.listAuditEvents.mockResolvedValue({ data: [] });
    const wrapper = mountPage(FoundationWorkbench);
    await flushPromises();

    expect(wrapper.find('[data-testid="foundation-error"]').text()).toContain('FOUNDATION_STORE_FAILED');
    expect(wrapper.find('[data-testid="foundation-retry"]').exists()).toBe(true);
  });

  it('VUE-04 挂载后按最小权限进入正式报表路由', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/reporting/operation', component: { template: '<div>reporting</div>' } },
        { path: '/terminal/registry', component: { template: '<div>terminal</div>' } }
      ]
    });
    await router.push('/');
    await router.isReady();
    const wrapper = mountPage(AdvancedOperations, { global: { plugins: [ElementPlus, router], directives: { hasPermi } } });

    const reporting = wrapper.find('[data-testid="advanced-reporting-link"]');
    expect(reporting.attributes('data-permission')).toBe('report:operation:read');
    await reporting.trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.fullPath).toBe('/reporting/operation');
  });

  it('同一原操作身份并发点击只执行一次，未知结果保留原身份供人工恢复', async () => {
    let release!: (value: { ok: boolean }) => void;
    const pending = new Promise<{ ok: boolean }>((resolve) => {
      release = resolve;
    });
    const work = vi.fn(() => pending);
    const page = useRecoverablePage('PAGE_WRITE_FAILED');

    const first = page.runWrite('idem-seed-01', work);
    const second = page.runWrite('idem-seed-01', work);
    expect(second).toBe(first);
    expect(work).toHaveBeenCalledTimes(1);

    release({ ok: true });
    await first;
    expect(page.phase.value).toBe('READY');

    const unknown = vi.fn().mockRejectedValue(Object.assign(new Error('seed-timeout'), { isAxiosError: true }));
    await page.runWrite('idem-seed-01', unknown);
    expect(page.phase.value).toBe('UNKNOWN');
    expect(page.failure.value?.operationIdentity).toBe('idem-seed-01');

    await page.runRead(() => Promise.resolve({ reconciled: false }));
    expect(page.phase.value).toBe('UNKNOWN');
    expect(page.failure.value?.operationIdentity).toBe('idem-seed-01');

    await page.runWrite('idem-seed-01', unknown);
    expect(unknown).toHaveBeenCalledTimes(1);
    expect(page.failure.value?.operationIdentity).toBe('idem-seed-01');
  });
});
