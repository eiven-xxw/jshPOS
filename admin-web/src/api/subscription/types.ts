export interface SubscriptionRecord {
  subscriptionId: string;
  tenantId: string;
  planId: number;
  entitlementVersionId: string;
  contractRef: string;
  externalOrderRef: string;
  state: string;
  stateVersion: number;
  currentTermVersion: number;
  startsAt: string;
  endsAt: string;
  graceEndsAt: string;
  businessTimeZone: string;
  degradationPolicyVersion: string;
  contentSha256: string;
}
export interface SubscriptionTerm {
  termId: string;
  termVersion: number;
  startsAt: string;
  endsAt: string;
  graceEndsAt: string;
  contractRef: string;
  externalOrderRef: string;
  termSha256: string;
}
export interface SubscriptionDetail {
  subscription: SubscriptionRecord;
  terms: SubscriptionTerm[];
  accessMode: 'NORMAL' | 'GRACE' | 'RECOVERY_ONLY' | 'TERMINATED_RECOVERY' | 'NO_ACCESS_EFFECT';
  retainedCapabilities: string[];
}
export interface SubscriptionTermInput {
  contractRef: string;
  externalOrderRef: string;
  startsAt: string;
  endsAt: string;
  graceEndsAt: string;
  businessTimeZone: string;
}
