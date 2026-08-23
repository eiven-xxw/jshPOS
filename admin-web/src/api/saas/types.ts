export interface SaasApplication {
  applicationId: string;
  applicationCode: string;
  tenantId?: string;
  companyName: string;
  industry: string;
  planId: number;
  state: string;
  recordVersion: number;
  contentSha256: string;
}
export interface TenantEntitlement {
  tenantId: string;
  planId: number;
  versionId: string;
  lifecycleState: string;
  lifecycleVersion: number;
}
export interface SaasApplicationDetail {
  application: SaasApplication;
  checkpoints: string[];
  lifecycle?: TenantEntitlement;
}
export interface EntitlementVersion {
  versionId: string;
  planId: number;
  versionNo: number;
  state: string;
  effectiveAt: string;
  expiresAt?: string;
  contentSha256: string;
}
export interface SaasPlan {
  planId: number;
  planCode: string;
  planName: string;
  platformPackageId: number;
  accountLimit: number;
  status: string;
}
