// @vitest-environment happy-dom
import ElementPlus from 'element-plus';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const migrationApi = vi.hoisted(() => ({
  activateMigration: vi.fn(),
  approveMigration: vi.fn(),
  cleanupMigration: vi.fn(),
  createMigrationBatch: vi.fn(),
  getMigrationErrors: vi.fn(),
  getMigrationBatch: vi.fn(),
  reconcileMigration: vi.fn(),
  resumeMigration: vi.fn(),
  uploadMigrationFile: vi.fn()
}));
vi.mock('@/api/migration', () => migrationApi);
vi.mock('@/api/migration/contract', () => ({ sha256Hex: vi.fn(() => Promise.resolve('a'.repeat(64))) }));
vi.mock('@/api/operations', () => ({ newOperationCommandId: vi.fn(() => '01J00000000000000000000001') }));

import BusinessMigration from '../business-migration/index.vue';

const hasPermi = {
  mounted(element: HTMLElement, binding: { value: string[] }) {
    element.dataset.permission = binding.value.join(',');
  }
};

describe('G9A-R3B R6 VUE-05 业务迁移页面', () => {
  beforeEach(() => vi.clearAllMocks());

  it('读取失败展示脱敏错误、关联标识和恢复入口', async () => {
    migrationApi.getMigrationBatch.mockRejectedValue({
      response: {
        status: 403,
        data: { code: 'MIGRATION_SCOPE_DENIED', msg: '无权访问该迁移批次' },
        headers: { 'x-correlation-id': 'corr-vue05-denied' }
      }
    });
    const wrapper = mount(BusinessMigration, {
      global: { plugins: [ElementPlus], directives: { hasPermi }, stubs: { transition: false, Teleport: true } }
    });
    const inputs = wrapper.findAll('input');
    const batchInput = inputs.find((item) => item.attributes('class')?.includes('el-input__inner'))!;
    await batchInput.setValue('01J00000000000000000000001');
    await wrapper.find('[data-testid="migration-read"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="vue-05-error"]').text()).toContain('MIGRATION_SCOPE_DENIED');
    expect(wrapper.find('[data-testid="vue-05-error"]').text()).toContain('corr-vue05-denied');
  });
});
