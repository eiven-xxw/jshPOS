import { describe, expect, it } from 'vitest';
import {
  buildOperationConfirmation,
  buildOperationsSummary,
  createSingleFlight,
  exactDecimal,
  normalizeProductUnits,
  parseSafePlatformIds
} from '../model';

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

  it('freezes a high-risk operation confirmation with server state version and stable idempotency key', () => {
    const confirmation = buildOperationConfirmation({
      owner: 'Inventory',
      objectId: '01J00000000000000000000001',
      currentState: 'REVIEWED',
      currentVersion: 3,
      action: 'approve',
      impact: '追加盘盈盘亏流水并重建余额投影',
      reason: '复核差异后批准入账',
      idempotencyKey: '01J00000000000000000000002'
    });
    expect(Object.isFrozen(confirmation)).toBe(true);
    expect(confirmation.currentVersion).toBe(3);
    expect(() => buildOperationConfirmation({ ...confirmation, currentVersion: -1, reason: 'x' })).toThrow('ADM-OP-001');
  });

  it('keeps decimal quantities as exact text and rejects float-like unsafe inputs', () => {
    expect(exactDecimal('12.340000')).toBe('12.340000');
    expect(exactDecimal('-1.5', true)).toBe('-1.5');
    expect(() => exactDecimal('1e3')).toThrow('ADM-INPUT-004');
    expect(() => exactDecimal('-1')).toThrow('ADM-INPUT-004');
  });

  it('parses bounded platform ids without losing integer precision', () => {
    expect(parseSafePlatformIds('1, 2，2 3')).toEqual([1, 2, 3]);
    expect(() => parseSafePlatformIds('9007199254740992')).toThrow('ADM-INPUT-003');
    expect(() => parseSafePlatformIds('')).toThrow('ADM-INPUT-003');
  });

  it('deduplicates repeated submits while allowing a later explicit retry', async () => {
    const singleFlight = createSingleFlight();
    let calls = 0;
    const task = () => {
      calls += 1;
      return Promise.resolve(calls);
    };
    const first = singleFlight(task);
    const duplicate = singleFlight(task);
    expect(await first).toBe(1);
    expect(await duplicate).toBe(1);
    expect(await singleFlight(task)).toBe(2);
  });
});
