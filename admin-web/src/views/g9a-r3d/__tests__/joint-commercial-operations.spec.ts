// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { KeepAlive, defineComponent, h } from 'vue';
import { RouterView, createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const operationApi = vi.hoisted(() => {
  let sequence = 0;
  return {
    newOperationCommandId: vi.fn(() => `01J${String(++sequence).padStart(23, '0')}`),
    reset: () => (sequence = 0),
    createRelease: vi.fn(),
    createRollout: vi.fn(),
    getRelease: vi.fn(),
    transitionRelease: vi.fn(),
    transitionRollout: vi.fn()
  };
});
const saasApi = vi.hoisted(() => ({
  activateSaasApplication: vi.fn(),
  advanceEntitlementVersion: vi.fn(),
  approveSaasApplication: vi.fn(),
  changeTenantLifecycle: vi.fn(),
  createEntitlementVersion: vi.fn(),
  createSaasApplication: vi.fn(),
  createSaasPlan: vi.fn(),
  getSaasApplication: vi.fn(),
  initializeSaasApplication: vi.fn(),
  preflightSaasApplication: vi.fn(),
  provisionSaasApplication: vi.fn()
}));
const subscriptionApi = vi.hoisted(() => ({
  activateSubscription: vi.fn(),
  createSubscription: vi.fn(),
  getSubscription: vi.fn(),
  renewSubscription: vi.fn(),
  requestSubscriptionTermination: vi.fn(),
  restoreSubscription: vi.fn(),
  suspendSubscription: vi.fn()
}));
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
const terminalApi = vi.hoisted(() => ({
  changeTerminalStatus: vi.fn(),
  issueTerminalActivation: vi.fn(),
  listTerminals: vi.fn(),
  rotateTerminalCredential: vi.fn()
}));

vi.mock('@/api/operations', () => operationApi);
vi.mock('@/api/saas', () => saasApi);
vi.mock('@/api/subscription', () => subscriptionApi);
vi.mock('@/api/service', () => serviceApi);
vi.mock('@/api/terminal', () => terminalApi);
vi.mock('@/api/terminal/contract', () => ({ newTerminalCommandKey: vi.fn(() => 'terminal:joint:original-command') }));

import ReleasePanel from '../../operations/components/ReleasePanel.vue';
import SaasOperations from '../../saas/operations/index.vue';
import ServiceOperations from '../../service/operations/index.vue';
import SubscriptionOperations from '../../subscription/operations/index.vue';
import TerminalRegistry from '../../terminal/registry/index.vue';

const trustedContext = Object.freeze({
  platformRole: 'PLATFORM_OPERATOR_SYNTHETIC',
  tenantRef: 'TENANT_SYNTH_A',
  storeScope: '1101,1102',
  entitlementVersion: 'ENTITLEMENT_SYNTH_V1'
});

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

const commercialJourney = [
  {
    id: 'VUE-17',
    path: '/joint/saas',
    marker: '[data-testid="saas-state"]',
    button: '[data-testid="saas-lifecycle-terminate-logical"]',
    routePermission: 'saas:application:read',
    buttonPermission: 'saas:tenant:lifecycle',
    boundary: '本页面不执行真实收费、支付、设备、伙伴现场或生产开户',
    component: SaasOperations
  },
  {
    id: 'VUE-19',
    path: '/joint/subscription',
    marker: '[data-testid="subscription-state"]',
    button: '[data-testid="subscription-read"]',
    routePermission: 'subscription:read',
    buttonPermission: 'subscription:read',
    boundary: '本页面不执行真实计费、扣款、支付 Provider、发票或资金结算',
    component: SubscriptionOperations
  },
  {
    id: 'VUE-18',
    path: '/joint/service',
    marker: '[data-testid="service-state"]',
    button: '[data-testid="service-project-refresh"]',
    routePermission: 'service:read',
    buttonPermission: 'service:project:read',
    boundary: '内部时间目标不构成合同 SLA',
    component: ServiceOperations
  },
  {
    id: 'VUE-20',
    path: '/joint/terminal',
    marker: '[data-testid="terminal-state"]',
    button: '[data-testid="terminal-issue-open"]',
    routePermission: 'terminal:registry:read',
    buttonPermission: 'terminal:activation:issue',
    boundary: '真实设备验收仍为 BLOCKED',
    component: TerminalRegistry
  },
  {
    id: 'VUE-09',
    path: '/joint/release',
    marker: '[data-testid="vue-09-state"]',
    button: '[data-testid="release-read"]',
    routePermission: 'release:read',
    buttonPermission: 'release:read',
    boundary: '不发送固件、重启或真实远程命令',
    component: ReleasePanel
  }
] as const;

const JointHost = defineComponent({
  name: 'G9aR3dJointCommercialHost',
  setup() {
    return () =>
      h('main', { 'data-testid': 'joint-commercial-host' }, [
        h('aside', {
          'data-testid': 'joint-commercial-context',
          'data-platform-role': trustedContext.platformRole,
          'data-tenant': trustedContext.tenantRef,
          'data-store-scope': trustedContext.storeScope,
          'data-entitlement': trustedContext.entitlementVersion
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
    routes: commercialJourney.map(
      (surface): RouteRecordRaw => ({
        path: surface.path,
        name: surface.id,
        component: surface.component,
        meta: { permission: surface.routePermission, authority: 'SERVER_SESSION_AND_OWNER' }
      })
    )
  });

describe('G9A-R3D R2 SaaS、订阅、服务、终端与发布联合旅程', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    operationApi.reset();
    terminalApi.listTerminals.mockResolvedValue({ data: { items: [], total: 0, page: 1, size: 50 } });
    serviceApi.listServiceProjects.mockResolvedValue({ data: [] });
    serviceApi.listServiceTickets.mockResolvedValue({ data: [] });
  });

  it('在同一平台角色与租户范围中逐页保持最小权限和外部失败关闭', async () => {
    const router = createJointRouter();
    await router.push(commercialJourney[0].path);
    await router.isReady();
    const wrapper = mount(JointHost, {
      attachTo: document.body,
      global: {
        plugins: [ElementPlus, router],
        directives: { hasPermi },
        stubs: { Teleport: true, teleport: true, transition: false, Pagination: true }
      }
    });

    for (const surface of commercialJourney) {
      await router.push(surface.path);
      await flushPromises();
      expect(router.currentRoute.value.name, surface.id).toBe(surface.id);
      expect(router.currentRoute.value.meta.authority, surface.id).toBe('SERVER_SESSION_AND_OWNER');
      expect(wrapper.find(surface.marker).exists(), surface.id).toBe(true);
      expect(router.currentRoute.value.meta.permission, surface.id).toBe(surface.routePermission);
      expect(wrapper.get(surface.button).attributes('data-permission'), surface.id).toBe(surface.buttonPermission);
      expect(wrapper.text(), surface.id).toContain(surface.boundary);
      const context = wrapper.get('[data-testid="joint-commercial-context"]');
      expect(context.attributes('data-platform-role')).toBe(trustedContext.platformRole);
      expect(context.attributes('data-tenant')).toBe(trustedContext.tenantRef);
      expect(context.attributes('data-store-scope')).toBe(trustedContext.storeScope);
      expect(context.attributes('data-entitlement')).toBe(trustedContext.entitlementVersion);
    }

    expect(JSON.stringify(terminalApi.listTerminals.mock.calls)).not.toContain(trustedContext.tenantRef);
  });

  it('终端读取越权在跨页返回后仍失败关闭且不伪造设备成功', async () => {
    terminalApi.listTerminals.mockRejectedValue(
      Object.assign(new Error('joint-terminal-scope'), {
        response: {
          status: 403,
          data: { code: 'TERMINAL_SCOPE_DENIED', msg: '终端不在当前门店范围' },
          headers: { 'x-correlation-id': 'corr-r3d-commercial-001' }
        }
      })
    );
    const router = createJointRouter();
    await router.push('/joint/terminal');
    await router.isReady();
    const wrapper = mount(JointHost, {
      attachTo: document.body,
      global: {
        plugins: [ElementPlus, router],
        directives: { hasPermi },
        stubs: { Teleport: true, teleport: true, transition: false, Pagination: true }
      }
    });
    await flushPromises();
    expect(wrapper.get('[data-testid="terminal-error"]').text()).toContain('TERMINAL_SCOPE_DENIED');
    expect(terminalApi.listTerminals).toHaveBeenCalledTimes(1);

    await router.push('/joint/release');
    await flushPromises();
    expect(wrapper.text()).toContain('不发送固件、重启或真实远程命令');
    await router.push('/joint/terminal');
    await flushPromises();
    expect(wrapper.get('[data-testid="terminal-error"]').text()).toContain('corr-r3d-commercial-001');
    expect(wrapper.text()).toContain('真实设备验收仍为 BLOCKED');
    expect(terminalApi.listTerminals).toHaveBeenCalledTimes(1);
  });
});
