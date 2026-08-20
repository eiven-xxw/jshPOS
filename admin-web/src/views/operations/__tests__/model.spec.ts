import { describe, expect, it } from 'vitest';
import { buildOperationsSummary, normalizeProductUnits } from '../model';

describe('ADM-001 operations model', () => {
  it('normalizes exact multi-units without adding tenant identity', () => {
    const result = normalizeProductUnits([
      {
        rowKey: 'base',
        unitId: '101',
        ratioNumerator: 1,
        ratioDenominator: 1,
        primary: true,
        barcodesText: '690000000001, 690000000002'
      },
      {
        rowKey: 'case',
        unitId: '102',
        ratioNumerator: 12,
        ratioDenominator: 1,
        primary: false,
        barcodesText: '690000000012'
      }
    ]);

    expect(result).toHaveLength(2);
    expect(result[1].ratioNumerator).toBe(12);
    expect(result[0].barcodes).toEqual(['690000000001', '690000000002']);
    expect(JSON.stringify(result)).not.toContain('tenant');
  });

  it('rejects missing primary duplicate unit duplicate barcode and non-integer ratios', () => {
    const base = {
      rowKey: 'base',
      unitId: '101',
      ratioNumerator: 1,
      ratioDenominator: 1,
      primary: false,
      barcodesText: '690000000001'
    };
    expect(() => normalizeProductUnits([base])).toThrow('CAT-UNIT-UI-001');
    expect(() =>
      normalizeProductUnits([
        { ...base, primary: true },
        { ...base, rowKey: 'copy' }
      ])
    ).toThrow('CAT-UNIT-UI-002');
    expect(() =>
      normalizeProductUnits([
        { ...base, primary: true },
        { ...base, rowKey: 'case', unitId: '102', barcodesText: '690000000001' }
      ])
    ).toThrow('CAT-UNIT-UI-004');
    expect(() => normalizeProductUnits([{ ...base, primary: true, ratioNumerator: 1.5 }])).toThrow('CAT-UNIT-UI-003');
  });

  it('builds visible operational counters without financial calculations', () => {
    expect(
      buildOperationsSummary({
        orgStatuses: ['ACTIVE', 'ACTIVE'],
        storeStatuses: ['ACTIVE', 'PREPARING'],
        productStatuses: ['ACTIVE', 'DRAFT', 'INACTIVE'],
        configStatuses: ['ACTIVE'],
        auditResults: ['SUCCESS', 'DENIED', 'FAILURE']
      })
    ).toEqual({
      orgCount: 2,
      storeCount: 2,
      productCount: 3,
      configCount: 1,
      preparingStoreCount: 1,
      inactiveProductCount: 2,
      deniedAuditCount: 2
    });
  });
});
