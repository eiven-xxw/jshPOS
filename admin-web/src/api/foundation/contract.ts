import type { StaffScopeInput } from './types';

const TENANT_KEYS = new Set(['tenantid', 'tenant_id', 'tenant']);

export const FOUNDATION_ENDPOINTS = Object.freeze({
  orgUnits: '/api/v1/foundation/org-units',
  stores: '/api/v1/foundation/stores',
  staffScopes: '/api/v1/foundation/staff-scopes',
  config: '/api/v1/foundation/config',
  auditEvents: '/api/v1/foundation/audit-events'
});

/**
 * 客户端仅做早期误用防护；真正的租户边界由服务端可信会话和数据库约束负责。
 */
export function assertNoClientTenantOverride(value: unknown): void {
  if (Array.isArray(value)) {
    value.forEach(assertNoClientTenantOverride);
    return;
  }
  if (value && typeof value === 'object') {
    Object.entries(value as Record<string, unknown>).forEach(([key, item]) => {
      if (TENANT_KEYS.has(key.replace(/[-\s]/g, '').toLowerCase())) {
        throw new Error('FND-IAM-004: 客户端不得提交租户标识');
      }
      assertNoClientTenantOverride(item);
    });
  }
}

export function validateStaffScopeShape(scope: StaffScopeInput): boolean {
  if (scope.scopeType === 'TENANT') return scope.orgUnitId == null && scope.storeId == null;
  if (scope.scopeType === 'ORG_SUBTREE') return scope.orgUnitId != null && scope.storeId == null;
  return scope.storeId != null && scope.orgUnitId == null;
}
