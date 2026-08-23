import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const source = readFileSync(new URL('../daily-close/index.vue', import.meta.url), 'utf8');

describe('T2-CLS-001 daily close workbench', () => {
  it('shows serial close journey, append-only correction and external boundary', () => {
    for (const token of ['创建日结草稿', '完整预检', '独立审批', '签署并关闭', '扫描晚到事实']) expect(source).toContain(token);
    expect(source).toContain('BLOCKED/UNAVAILABLE');
    expect(source).toContain('不会伪造渠道对账通过');
    expect(source).toContain('只追加');
  });

  it('uses formal APIs and never calculates or writes owner facts', () => {
    expect(source).toContain('getDailyClose');
    expect(source).toContain('preflightDailyClose');
    expect(source).not.toContain('tenantId');
    expect(source).not.toContain('fetch(');
    expect(source).not.toContain('UPDATE ');
    expect(source).not.toContain('grossMinor -');
  });
});
