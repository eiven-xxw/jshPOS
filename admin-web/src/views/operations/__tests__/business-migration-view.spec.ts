import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const source = readFileSync(new URL('../business-migration/index.vue', import.meta.url), 'utf8');

describe('T2-DMT-001 business migration wizard', () => {
  it('exposes the guarded preflight, dual approval, saga, reconciliation and activation journey', () => {
    for (const token of ['上传并预检', '双人审批', '执行/恢复原 Saga', '逐 Owner 对账', '激活可见版本', '到期清理暂存']) {
      expect(source).toContain(token);
    }
    expect(source).toContain('getMigrationErrors');
    expect(source).toContain('el-pagination');
  });

  it('does not calculate owner facts or expose tenant and member identity fields', () => {
    expect(source).not.toContain('tenantId');
    expect(source).not.toContain('phone');
    expect(source).not.toContain('identityCiphertext');
    expect(source).not.toContain('fetch(');
    expect(source).not.toContain('Math.');
    expect(source).toContain('不自动开店');
  });
});
