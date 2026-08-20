import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const readView = (relative: string) => readFileSync(new URL(relative, import.meta.url), 'utf8');

describe('ADM-001 formal Vue view boundaries', () => {
  it('provides a business operations home instead of the framework demo home', () => {
    const source = readView('../../index.vue');
    expect(source).toContain('经营工作台');
    expect(source).toContain('员工与角色');
    expect(source).toContain('商品价格');
    expect(source).not.toContain('RuoYi-Cloud-Plus');
    expect(source).not.toMatch(/tenant_?id\s*[:=]/i);
  });

  it('uses formal foundation APIs for staff scopes and preserves trusted tenant context', () => {
    const source = readView('../../foundation/index.vue');
    expect(source).toContain('listStaffScopes');
    expect(source).toContain('replaceStaffScopes');
    expect(source).toContain('foundation:scope:grant');
    expect(source).not.toMatch(/tenant_?id\s*[:=]/i);
    expect(source).not.toContain("url: '/api");
  });

  it('supports brand multi-unit import errors and price publication through catalog APIs', () => {
    const source = readView('../../catalog/index.vue');
    for (const token of ['createBrand', 'normalizeProductUnits', 'importResult.errors', 'publishPriceBook', 'rollbackImport']) {
      expect(source).toContain(token);
    }
    expect(source).not.toMatch(/tenant_?id/i);
    expect(source).not.toContain('fetch(');
    expect(source).not.toContain("url: '/api");
  });
});
