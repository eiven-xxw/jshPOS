export type OrgUnitType = 'HEADQUARTERS' | 'REGION' | 'COMPANY' | 'OTHER';
export type ActiveStatus = 'ACTIVE' | 'INACTIVE';
export type StoreStatus = 'PREPARING' | ActiveStatus;
export type Industry = 'CONVENIENCE' | 'SNACK_DISCOUNT' | 'COMMUNITY_SUPERMARKET';
export type ScopeType = 'TENANT' | 'ORG_SUBTREE' | 'STORE';
export type ConfigTargetType = 'TENANT' | 'STORE';

export interface OrgUnitVO {
  orgUnitId: number;
  parentId?: number;
  code: string;
  name: string;
  type: OrgUnitType;
  status: ActiveStatus;
  treeDepth: number;
  version: number;
}

export interface CreateOrgUnitForm {
  parentId?: number;
  code: string;
  name: string;
  type: OrgUnitType;
}

export interface UpdateOrgUnitForm extends CreateOrgUnitForm {
  status: ActiveStatus;
  version: number;
}

export interface StoreVO {
  storeId: number;
  orgUnitId: number;
  platformDeptId?: number;
  code: string;
  name: string;
  zoneId: string;
  businessDayStart: string;
  status: StoreStatus;
  version: number;
}

export interface CreateStoreForm {
  orgUnitId: number;
  platformDeptId?: number;
  code: string;
  name: string;
  zoneId: string;
  businessDayStart: string;
}

export interface UpdateStoreForm extends CreateStoreForm {
  status: StoreStatus;
  version: number;
}

export interface BusinessDateVO {
  storeId: number;
  zoneId: string;
  businessDayStart: string;
  instant: string;
  businessDate: string;
}

export interface StaffScopeInput {
  scopeType: ScopeType;
  orgUnitId?: number;
  storeId?: number;
}

export interface StaffScopeVO extends StaffScopeInput {
  staffScopeId: number;
  userId: number;
  status: 'ACTIVE' | 'REVOKED';
  version: number;
}

export interface ConfigTemplateVO {
  templateId: number;
  code: string;
  name: string;
  industry: Industry;
  status: ActiveStatus;
  version: number;
}

export interface ConfigVersionVO {
  configVersionId: number;
  templateId: number;
  versionNo: number;
  schemaVersion: string;
  state: 'DRAFT' | 'PUBLISHED' | 'RETIRED';
  contentSha256: string;
}

export interface ConfigBindingVO {
  bindingId: number;
  templateId: number;
  targetType: ConfigTargetType;
  targetId?: number;
  currentVersionId: number;
  previousVersionId?: number;
  version: number;
}

export interface AuditEventVO {
  auditId: number;
  correlationId: string;
  actionCode: string;
  targetType: string;
  targetId: string;
  result: 'SUCCESS' | 'FAILURE' | 'DENIED';
  occurredAt: string;
  beforeSha256?: string;
  afterSha256?: string;
  summary: Record<string, unknown>;
}

export interface CreateConfigTemplateForm {
  code: string;
  name: string;
  industry: Industry;
}

export interface CreateConfigVersionForm {
  schemaVersion: string;
  content: Record<string, unknown>;
}

export interface ActivateConfigForm {
  templateId: number;
  configVersionId: number;
  targetType: ConfigTargetType;
  targetId?: number;
}
