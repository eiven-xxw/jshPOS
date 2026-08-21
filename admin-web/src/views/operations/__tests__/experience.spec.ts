import { describe, expect, it } from 'vitest';
import { INDUSTRY_EXPERIENCE_OPTIONS, resolveIndustryExperience } from '../experience';

describe('Gate 6H industry experience', () => {
  it('maps exactly three commercial V1 industries without domain facts', () => {
    expect(INDUSTRY_EXPERIENCE_OPTIONS.map((item) => item.value)).toEqual(['CONVENIENCE', 'SNACK_DISCOUNT', 'COMMUNITY_SUPERMARKET']);
    expect(resolveIndustryExperience('SNACK_DISCOUNT').primaryActions).toContain('促销会员');
    const serialized = JSON.stringify(resolveIndustryExperience('COMMUNITY_SUPERMARKET'));
    expect(serialized).not.toMatch(/tenant_?id|amount|stockQuantity|costAmount/i);
  });

  it('returns frozen display configuration', () => {
    expect(Object.isFrozen(resolveIndustryExperience('CONVENIENCE'))).toBe(true);
  });
});
