import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const source = readFileSync(new URL('../components/ReplenishmentPanel.vue', import.meta.url), 'utf8');

describe('T2-RPL-001 replenishment operations view', () => {
  it('shows server explanations and requires controlled state actions', () => {
    for (const token of [
      'availableQuantity',
      'confirmedInTransitQuantity',
      'suggestedPurchaseQuantity',
      'reasonCode',
      'currentState',
      'currentVersion',
      'runControlled'
    ])
      expect(source).toContain(token);
  });

  it('does not calculate replenishment or cross owner boundaries in the browser', () => {
    expect(source).not.toContain('Math.ceil');
    expect(source).not.toContain('Mapper');
    expect(source).not.toContain('tenantId');
    expect(source).not.toContain('fetch(');
    expect(source).toContain('不自动下单');
    expect(source).toContain('只创建采购 DRAFT');
  });
});
