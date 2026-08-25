// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { KeepAlive, defineComponent, h } from 'vue';
import { RouterView, createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const catalogApi = vi.hoisted(() => ({
  addPriceItem: vi.fn(),
  changeProductState: vi.fn(),
  confirmShelfLabelReplacement: vi.fn(),
  createBrand: vi.fn(),
  createCategory: vi.fn(),
  createPriceBook: vi.fn(),
  createProduct: vi.fn(),
  createShelfLabelTemplate: vi.fn(),
  createUnit: vi.fn(),
  dispatchShelfLabelTask: vi.fn(),
  getShelfLabelTask: vi.fn(),
  listProducts: vi.fn(),
  listShelfLabelTasks: vi.fn(),
  listShelfLabelTemplates: vi.fn(),
  preflightImport: vi.fn(),
  previewShelfLabelItem: vi.fn(),
  publishImport: vi.fn(),
  publishPriceBook: vi.fn(),
  publishShelfLabelTemplate: vi.fn(),
  recordShelfLabelException: vi.fn(),
  retireShelfLabelTemplate: vi.fn(),
  rollbackImport: vi.fn()
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
const migrationApi = vi.hoisted(() => ({
  activateMigration: vi.fn(),
  approveMigration: vi.fn(),
  cleanupMigration: vi.fn(),
  createMigrationBatch: vi.fn(),
  getMigrationBatch: vi.fn(),
  getMigrationErrors: vi.fn(),
  reconcileMigration: vi.fn(),
  resumeMigration: vi.fn(),
  uploadMigrationFile: vi.fn()
}));
const operationsApi = vi.hoisted(() => {
  let sequence = 0;
  const command = () => `01J${String(++sequence).padStart(23, '0')}`;
  const names = [
    'adjustMemberPoints',
    'changeSupplierState',
    'confirmProcurementReceipt',
    'createMember',
    'createPrivacyRequest',
    'createProcurementOrder',
    'createProcurementReceipt',
    'createProcurementReturn',
    'createPromotionRule',
    'createRelease',
    'createReplenishmentPolicy',
    'createReplenishmentPurchaseDraft',
    'createRollout',
    'createStocktake',
    'createSupplier',
    'createTransfer',
    'dispatchTransfer',
    'generateReplenishmentSuggestions',
    'getCostBalance',
    'getCostLedger',
    'getInventoryBalance',
    'getInventoryLedger',
    'getMemberPoints',
    'getProcurementOrder',
    'getProcurementReceipt',
    'getRelease',
    'getStocktake',
    'getTransfer',
    'listReplenishmentPolicies',
    'listReplenishmentSuggestions',
    'receiveTransfer',
    'rebuildCostBalance',
    'rebuildInventoryBalance',
    'recordMemberConsent',
    'recordStocktakeCount',
    'resolveMember',
    'transitionProcurementOrder',
    'transitionProcurementReturn',
    'transitionPromotionRule',
    'transitionRelease',
    'transitionReplenishmentPolicy',
    'transitionReplenishmentSuggestion',
    'transitionRollout',
    'transitionStocktake',
    'transitionTransfer'
  ] as const;
  const result: Record<string, ReturnType<typeof vi.fn>> = {};
  for (const name of names) result[name] = vi.fn();
  return { ...result, newOperationCommandId: vi.fn(command), resetSequence: () => (sequence = 0) };
});
const benefitApi = vi.hoisted(() => ({
  createBenefitPolicy: vi.fn(),
  createMemberPriceVersion: vi.fn(),
  publishMemberBenefitPackage: vi.fn(),
  transitionBenefitPolicy: vi.fn(),
  transitionMemberPrice: vi.fn()
}));
const dailyCloseApi = vi.hoisted(() => ({
  approveDailyClose: vi.fn(),
  createDailyClose: vi.fn(),
  detectDailyCloseLateFacts: vi.fn(),
  getDailyClose: vi.fn(),
  listDailyCloses: vi.fn(),
  preflightDailyClose: vi.fn(),
  signDailyClose: vi.fn()
}));
const exceptionApi = vi.hoisted(() => ({
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
const lotApi = vi.hoisted(() => ({ getEffectiveLotPolicy: vi.fn(), listLotExpiryAlerts: vi.fn(), publishLotPolicy: vi.fn() }));
const onboardingApi = vi.hoisted(() => ({
  applyOnboardingPlan: vi.fn(),
  approveOnboardingPlan: vi.fn(),
  cancelOnboardingPlan: vi.fn(),
  checkOnboardingPlan: vi.fn(),
  createOnboardingPlan: vi.fn(),
  getOnboardingPlan: vi.fn(),
  openOnboardingStore: vi.fn(),
  preflightOnboardingPlan: vi.fn()
}));
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
  requestReportExport: vi.fn()
}));

vi.mock('@/api/catalog', () => catalogApi);
vi.mock('@/api/catalog/contract', () => ({ shelfLabelCommandIdentity: vi.fn(() => 'shelf-label:joint-seed') }));
vi.mock('@/api/foundation', () => foundationApi);
vi.mock('@/api/migration', () => migrationApi);
vi.mock('@/api/migration/contract', () => ({ sha256Hex: vi.fn(() => Promise.resolve('a'.repeat(64))) }));
vi.mock('@/api/operations', () => operationsApi);
vi.mock('@/api/member-benefit', () => benefitApi);
vi.mock('@/api/daily-close', () => dailyCloseApi);
vi.mock('@/api/exception-center', () => exceptionApi);
vi.mock('@/api/lot-expiry', () => lotApi);
vi.mock('@/api/onboarding', () => onboardingApi);
vi.mock('@/api/reporting', () => reportingApi);
vi.mock('@/api/reporting/contract', () => ({
  newUlid: vi.fn(() => '01J00000000000000000000001'),
  parseStoreIds: vi.fn(() => [1101])
}));
vi.mock('file-saver', () => ({ saveAs: vi.fn() }));

import CatalogWorkbench from '../../catalog/index.vue';
import ShelfLabelPanel from '../../catalog/components/ShelfLabelPanel.vue';
import FoundationWorkbench from '../../foundation/index.vue';
import AdvancedOperations from '../../operations/advanced/index.vue';
import BusinessMigration from '../../operations/business-migration/index.vue';
import CustomerPromotionPanel from '../../operations/components/CustomerPromotionPanel.vue';
import InventoryCostPanel from '../../operations/components/InventoryCostPanel.vue';
import MemberBenefitPolicyPanel from '../../operations/components/MemberBenefitPolicyPanel.vue';
import ReplenishmentPanel from '../../operations/components/ReplenishmentPanel.vue';
import SupplyPanel from '../../operations/components/SupplyPanel.vue';
import DailyClosePage from '../../operations/daily-close/index.vue';
import ExceptionCenterPage from '../../operations/exception-center/index.vue';
import LotExpiryPage from '../../operations/lot-expiry/index.vue';
import StoreOnboardingPage from '../../operations/store-onboarding/index.vue';
import ReportingOperation from '../../reporting/operation/index.vue';

const trustedContext = Object.freeze({
  tenantRef: 'TENANT_SYNTH_A',
  organizationRef: 'ORG_SYNTH_A',
  storeRef: '1101',
  businessDate: '2026-08-25'
});

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

const wrapped = (name: string, component: Parameters<typeof h>[0], props: Record<string, unknown> = {}) =>
  defineComponent({ name, setup: () => () => h(component, props) });

const mainJourney = [
  {
    id: 'VUE-01',
    path: '/joint/catalog',
    marker: '[data-testid="catalog-refresh"]',
    permission: 'catalog:product:query',
    component: CatalogWorkbench
  },
  {
    id: 'VUE-02',
    path: '/joint/shelf-label',
    marker: '[data-testid="vue-02-surface"]',
    permission: 'catalog:shelf-label:template:create',
    component: wrapped('JointShelfLabel', ShelfLabelPanel, { modelValue: true, stores: [] })
  },
  {
    id: 'VUE-03',
    path: '/joint/foundation',
    marker: '[data-testid="foundation-refresh"]',
    permission: 'foundation:org:query',
    component: FoundationWorkbench
  },
  {
    id: 'VUE-04',
    path: '/joint/advanced',
    marker: '[data-testid="advanced-reporting-link"]',
    permission: 'operations:advanced:read',
    component: AdvancedOperations
  },
  { id: 'VUE-05', path: '/joint/migration', marker: '[data-testid="vue-05-state"]', permission: 'migration:read', component: BusinessMigration },
  {
    id: 'VUE-06',
    path: '/joint/customer-promotion',
    marker: '[data-testid="vue-06-state"]',
    permission: 'member:profile:read',
    component: CustomerPromotionPanel
  },
  {
    id: 'VUE-07',
    path: '/joint/inventory-cost',
    marker: '[data-testid="vue-07-state"]',
    permission: 'inventory:balance:read',
    component: InventoryCostPanel
  },
  {
    id: 'VUE-08',
    path: '/joint/member-benefit',
    marker: '[data-testid="vue-08-state"]',
    permission: 'member:benefit:read',
    component: MemberBenefitPolicyPanel
  },
  {
    id: 'VUE-10',
    path: '/joint/replenishment',
    marker: '[data-testid="vue-10-state"]',
    permission: 'replenishment:read',
    component: ReplenishmentPanel
  },
  { id: 'VUE-11', path: '/joint/supply', marker: '[data-testid="vue-11-state"]', permission: 'procurement:read', component: SupplyPanel },
  {
    id: 'VUE-12',
    path: '/joint/daily-close',
    marker: '[data-testid="vue-12-state"]',
    permission: 'operations:daily-close:read',
    component: DailyClosePage
  },
  {
    id: 'VUE-13',
    path: '/joint/exception-center',
    marker: '[data-testid="vue-13-state"]',
    permission: 'operations:exception:read',
    component: ExceptionCenterPage
  },
  {
    id: 'VUE-14',
    path: '/joint/lot-expiry',
    marker: '[data-testid="vue-14-state"]',
    permission: 'catalog:lot-policy:read',
    component: LotExpiryPage
  },
  {
    id: 'VUE-15',
    path: '/joint/onboarding',
    marker: '[data-testid="vue-15-state"]',
    permission: 'onboarding:plan:read',
    component: StoreOnboardingPage
  },
  {
    id: 'VUE-16',
    path: '/joint/reporting',
    marker: '[data-testid="reporting-state"]',
    permission: 'report:operation:read',
    component: ReportingOperation
  }
] as const;

const JointHost = defineComponent({
  name: 'G9aR3dJointMainHost',
  setup() {
    return () =>
      h('main', { 'data-testid': 'joint-main-host' }, [
        h('aside', {
          'data-testid': 'joint-trusted-context',
          'data-tenant': trustedContext.tenantRef,
          'data-store': trustedContext.storeRef,
          'data-business-date': trustedContext.businessDate
        }),
        h(RouterView, null, {
          default: ({ Component }: { Component: Parameters<typeof h>[0] }) => h(KeepAlive, null, [h(Component)])
        })
      ]);
  }
});

const createJointRouter = () =>
  createRouter({
    history: createMemoryHistory(),
    routes: mainJourney.map(
      (surface): RouteRecordRaw => ({
        path: surface.path,
        name: surface.id,
        component: surface.component,
        meta: { permission: surface.permission, trustedContextSource: 'SERVER_SESSION' }
      })
    )
  });

const emptyPage = { data: { items: [], total: 0, page: 1, size: 50 } };

describe('G9A-R3D R1 VUE-01..16 后台联合旅程', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    operationsApi.resetSequence();
    catalogApi.listProducts.mockResolvedValue({ data: [] });
    catalogApi.listShelfLabelTasks.mockResolvedValue({ data: [] });
    catalogApi.listShelfLabelTemplates.mockResolvedValue({ data: [] });
    foundationApi.listOrgUnits.mockResolvedValue({ data: [] });
    foundationApi.listStores.mockResolvedValue({ data: [] });
    foundationApi.listConfigTemplates.mockResolvedValue({ data: [] });
    foundationApi.listAuditEvents.mockResolvedValue({ data: [] });
    foundationApi.listStaffScopes.mockResolvedValue({ data: [] });
    foundationApi.getBusinessDate.mockResolvedValue({ data: { businessDate: trustedContext.businessDate } });
    dailyCloseApi.listDailyCloses.mockResolvedValue(emptyPage);
    exceptionApi.listExceptionCases.mockResolvedValue(emptyPage);
  });

  it('在同一 Router 与冻结上下文中真实挂载十五个路由承载的十六个页面', async () => {
    const router = createJointRouter();
    await router.push(mainJourney[0].path);
    await router.isReady();
    const wrapper = mount(JointHost, {
      attachTo: document.body,
      global: {
        plugins: [ElementPlus, router],
        directives: { hasPermi },
        stubs: {
          Teleport: true,
          teleport: true,
          transition: false,
          Pagination: true,
          ElDrawer: { template: '<section v-bind="$attrs"><slot /></section>' }
        }
      }
    });

    for (const surface of mainJourney) {
      await router.push(surface.path);
      await flushPromises();
      expect(router.currentRoute.value.name, surface.id).toBe(surface.id);
      expect(router.currentRoute.value.meta.permission, surface.id).toBe(surface.permission);
      expect(router.currentRoute.value.meta.trustedContextSource, surface.id).toBe('SERVER_SESSION');
      expect(wrapper.find(surface.marker).exists(), surface.id).toBe(true);
      const context = wrapper.get('[data-testid="joint-trusted-context"]');
      expect(context.attributes('data-tenant')).toBe(trustedContext.tenantRef);
      expect(context.attributes('data-store')).toBe(trustedContext.storeRef);
      expect(context.attributes('data-business-date')).toBe(trustedContext.businessDate);
    }

    const calls = [
      ...catalogApi.listProducts.mock.calls,
      ...foundationApi.listOrgUnits.mock.calls,
      ...foundationApi.listStores.mock.calls,
      ...dailyCloseApi.listDailyCloses.mock.calls,
      ...exceptionApi.listExceptionCases.mock.calls
    ];
    expect(JSON.stringify(calls)).not.toContain(trustedContext.tenantRef);
  });

  it('跨页返回统一异常中心后保留 UNKNOWN 与原操作，禁止生成替代修复命令', async () => {
    exceptionApi.scanExceptionOwners.mockRejectedValue(
      Object.assign(new Error('joint-network-timeout'), {
        isAxiosError: true,
        response: {
          status: 503,
          data: { code: 'EXCEPTION_SCAN_UNKNOWN', msg: '扫描结果未知' },
          headers: { 'x-correlation-id': 'corr-r3d-main-001' }
        }
      })
    );
    const router = createJointRouter();
    await router.push('/joint/exception-center');
    await router.isReady();
    const wrapper = mount(JointHost, {
      attachTo: document.body,
      global: {
        plugins: [ElementPlus, router],
        directives: { hasPermi },
        stubs: {
          Teleport: true,
          teleport: true,
          transition: false,
          Pagination: true,
          ElDrawer: { template: '<section v-bind="$attrs"><slot /></section>' }
        }
      }
    });
    await flushPromises();

    await wrapper.get('[data-testid="exception-store"] input').setValue('1101');
    await wrapper.get('[data-testid="exception-scan"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="vue-13-error"]').text()).toContain('EXCEPTION_SCAN_UNKNOWN');
    expect(exceptionApi.scanExceptionOwners).toHaveBeenCalledTimes(1);

    await router.push('/joint/reporting');
    await flushPromises();
    await router.push('/joint/exception-center');
    await flushPromises();
    expect(wrapper.get('[data-testid="vue-13-error"]').text()).toContain('corr-r3d-main-001');
    await wrapper.get('[data-testid="exception-scan"]').trigger('click');
    await flushPromises();
    expect(exceptionApi.scanExceptionOwners).toHaveBeenCalledTimes(1);
  });
});
