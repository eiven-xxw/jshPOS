import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (relative: string) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const view = read('../advanced/index.vue');
const inventory = read('../components/InventoryCostPanel.vue');
const supply = read('../components/SupplyPanel.vue');
const replenishment = read('../components/ReplenishmentPanel.vue');
const commercial = read('../components/CustomerPromotionPanel.vue');
const release = read('../components/ReleasePanel.vue');
const orchestration = read('../useControlledOperation.ts');
const runtimeSources = [view, inventory, supply, replenishment, commercial, release, orchestration].join('\n');

describe('T2-ADM-002 advanced operations UI', () => {
  it('covers every admitted Owner through formal components or an existing formal page', () => {
    for (const token of ['InventoryCostPanel', 'SupplyPanel', 'CustomerPromotionPanel', 'ReleasePanel', 'openReporting', 'openTerminal']) {
      expect(view).toContain(token);
    }
    for (const token of ['getInventoryLedger', 'rebuildInventoryBalance', 'createStocktake', 'transitionStocktake']) {
      expect(inventory).toContain(token);
    }
    for (const token of ['createProcurementOrder', 'confirmProcurementReceipt', 'transitionProcurementReturn', 'receiveTransfer']) {
      expect(supply).toContain(token);
    }
    for (const token of [
      'createReplenishmentPolicy',
      'generateReplenishmentSuggestions',
      'transitionReplenishmentSuggestion',
      'createReplenishmentPurchaseDraft'
    ]) {
      expect(replenishment).toContain(token);
    }
    for (const token of ['createPromotionRule', 'transitionPromotionRule', 'recordMemberConsent', 'adjustMemberPoints']) {
      expect(commercial).toContain(token);
    }
    for (const token of ['createRelease', 'transitionRelease', 'createRollout', 'transitionRollout']) {
      expect(release).toContain(token);
    }
  });

  it('requires state version confirmation per-operation single-flight and idempotency reuse', () => {
    for (const token of ['buildOperationConfirmation', 'ElMessageBox.confirm', 'pending', 'retryKeys', 'STALE', 'UNKNOWN', 'unresolved']) {
      expect(orchestration).toContain(token);
    }
    expect(orchestration).toContain('currentState');
    expect(orchestration).toContain('currentVersion');
    expect(orchestration).toContain('idempotencyKey');
  });

  it('does not open forbidden data or device boundaries', () => {
    expect(runtimeSources).not.toMatch(/tenant_?id/i);
    expect(runtimeSources).not.toContain('Mapper');
    expect(runtimeSources).not.toContain('MethodChannel');
    expect(runtimeSources).not.toContain('SQLite');
    expect(runtimeSources).not.toContain('axios.create');
    expect(runtimeSources).not.toContain('fetch(');
    expect(runtimeSources).not.toMatch(/https?:\/\//);
  });

  it('keeps exact quantities as strings and forbids real PII in the candidate fixture', () => {
    expect(inventory).toContain('exactDecimal');
    expect(supply).toContain('exactDecimal');
    expect(commercial).toContain("startsWith('SYN-')");
    expect(commercial).toContain('只允许合成会员与既有促销规则');
  });
});
