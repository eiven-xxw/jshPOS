import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const source = readFileSync(new URL('../store-onboarding/index.vue', import.meta.url), 'utf8');

describe('T2-ONB-001 store onboarding wizard', () => {
  it('exposes the frozen serial journey and explicit external evidence boundary', () => {
    for (const token of ['创建冻结计划', '完整预检', '独立审批', '应用冻结配置', '执行开店检查', '确认开店']) {
      expect(source).toContain(token);
    }
    expect(source).toContain('外部 P0');
    expect(source).toContain('BLOCKED/UNAVAILABLE');
    expect(source).toContain('不伪造通过');
  });

  it('uses formal APIs and does not calculate or write owner facts', () => {
    expect(source).toContain('getOnboardingPlan');
    expect(source).toContain('checkOnboardingPlan');
    expect(source).not.toContain('tenantId');
    expect(source).not.toContain('fetch(');
    expect(source).not.toContain('Math.');
    expect(source).not.toContain('UPDATE ');
  });
});
