import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const source = readFileSync(new URL('../index.ts', import.meta.url), 'utf8');

describe('Gate 6E operations API boundary', () => {
  it('uses the shared request adapter and never creates a parallel network client', () => {
    expect(source).toContain("import request from '@/utils/request'");
    expect(source).not.toContain('axios.create');
    expect(source).not.toContain('fetch(');
    expect(source).not.toMatch(/https?:\/\//);
  });

  it('does not carry tenant identity or Provider endpoints', () => {
    expect(source).not.toMatch(/tenant_?id/i);
    expect(source).not.toMatch(/provider.*(sdk|http|callback)/i);
    expect(source).toContain('trustedOperationsPayload');
  });

  it('reuses the same idempotency key through release state commands', () => {
    expect(source).toContain("'X-Idempotency-Key': idempotencyKey");
    expect(source).toContain('expectedVersion');
  });
});
