import { describe, expect, it } from 'vitest';
import { MIGRATION_ENDPOINT, migrationUlid, trustedMigrationPayload } from '../contract';

describe('T2-DMT-001 business migration contract', () => {
  it('uses the versioned first-party migration endpoint', () => {
    expect(MIGRATION_ENDPOINT).toBe('/api/v1/business-migrations');
  });

  it('rejects nested tenant claims and accepts custody metadata', () => {
    expect(() => trustedMigrationPayload({ rows: [{ tenant_id: 'FAKE-B' }] })).toThrow('DMT-IAM-001');
    expect(() => trustedMigrationPayload({ sourceSystem: '虚构旧系统', custodyReference: 'CUSTODY:SYN-001' })).not.toThrow();
  });

  it('validates batch ULIDs before path concatenation', () => {
    expect(migrationUlid('01J00000000000000000000001')).toBe('01J00000000000000000000001');
    expect(() => migrationUlid('../tenant-b')).toThrow('DMT-INPUT-001');
  });
});
