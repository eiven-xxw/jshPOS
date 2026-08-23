import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

const source = fs.readFileSync(path.resolve(__dirname, '../components/MemberBenefitPolicyPanel.vue'), 'utf8');
const parent = fs.readFileSync(path.resolve(__dirname, '../components/CustomerPromotionPanel.vue'), 'utf8');

describe('T2-MEM-003 operations view boundary', () => {
  it('mounts the formal benefit and member-price journey', () => {
    expect(parent).toContain('<MemberBenefitPolicyPanel />');
    for (const token of [
      'createBenefitPolicy',
      "policyAction('validate')",
      "policyAction('approve')",
      "policyAction('publish')",
      'createMemberPriceVersion',
      "priceAction('validate')",
      "priceAction('approve')",
      "priceAction('publish')",
      'publishMemberBenefitPackage'
    ]) {
      expect(source).toContain(token);
    }
  });

  it('does not calculate prices or submit tenant identity in the Vue layer', () => {
    expect(source).not.toMatch(/tenantId|tenant_id|float|double/);
    expect(source).toContain('页面不会计算成交价');
    expect(source).toContain('默认关闭');
  });
});
