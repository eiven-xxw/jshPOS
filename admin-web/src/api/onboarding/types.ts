export type OnboardingState =
  | 'DRAFT'
  | 'PREFLIGHTING'
  | 'PREFLIGHT_FAILED'
  | 'READY'
  | 'APPROVED'
  | 'APPLYING'
  | 'APPLIED'
  | 'CHECKING'
  | 'CHECK_FAILED'
  | 'READY_TO_OPEN'
  | 'OPENED'
  | 'FAILED'
  | 'COMPENSATION_REQUIRED'
  | 'CANCELLED';

export type OnboardingCheckStatus = 'PASS' | 'FAIL' | 'BLOCKED' | 'UNAVAILABLE' | 'WARN';

/** 服务端权威计划投影；页面禁止推导或改写状态。 */
export interface OnboardingPlan {
  planId: string;
  sourceStoreId?: number;
  targetStoreId: number;
  templateId: number;
  templateVersionId: number;
  sourceStoreVersion?: number;
  targetStoreVersion: number;
  templateVersionNo: number;
  templateSha256: string;
  industry: 'CONVENIENCE' | 'SNACK_DISCOUNT' | 'COMMUNITY_SUPERMARKET';
  snapshotSha256: string;
  state: OnboardingState;
  checkRun: number;
  recordVersion: number;
  createdAt: string;
  updatedAt: string;
}

export interface OnboardingSnapshotItem {
  snapshotId: string;
  itemKey: string;
  contentJson: string;
  contentSha256: string;
  createdAt: string;
}

export interface OnboardingApproval {
  approvalId: string;
  approverUserId: number;
  reason: string;
  approvedAt: string;
}

export interface OnboardingCheckpoint {
  checkpointId: string;
  stepCode: string;
  resultSha256: string;
  state: string;
  createdAt: string;
}

export interface OnboardingCheck {
  checkId: string;
  runNo: number;
  checkCode: string;
  ownerType: string;
  required: boolean;
  external: boolean;
  factVersion: string;
  factSha256: string;
  status: OnboardingCheckStatus;
  maskedMessage: string;
  checkedAt: string;
}

export interface OnboardingPlanDetail {
  plan: OnboardingPlan;
  snapshot: OnboardingSnapshotItem[];
  approvals: OnboardingApproval[];
  checkpoints: OnboardingCheckpoint[];
  checks: OnboardingCheck[];
}
