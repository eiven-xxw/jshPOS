export type ExceptionCaseState = 'OPEN' | 'CLAIMED' | 'IN_PROGRESS' | 'WAITING_OWNER' | 'RESOLVED' | 'CLOSED' | 'REOPENED' | 'FAILED';

/** 服务端权威异常案件；页面不得自行推进状态或重算 Owner 结果。 */
export interface ExceptionCaseRecord {
  caseId: string;
  storeId: number;
  sourceOwner: string;
  sourceType: string;
  sourceFactId: string;
  dedupKey: string;
  severity: 'P0' | 'P1' | 'P2' | 'P3';
  state: ExceptionCaseState;
  latestSourceEventId: string;
  latestSourceSequence: number;
  latestSourceSha256: string;
  assigneeUserId?: number;
  leaseExpiresAt?: string;
  resolverUserId?: number;
  reviewerUserId?: number;
  recordVersion: number;
  firstObservedAt: string;
  lastObservedAt: string;
}

export interface ExceptionCaseDetail {
  exceptionCase: ExceptionCaseRecord;
  observations: Array<{
    observationId: string;
    sourceEventId: string;
    sourceSequence: number;
    sourceSha256: string;
    maskedSummary: string;
    conflictFlag: string;
    observedAt: string;
  }>;
  plans: Array<{ planId: string; actionCode: string; summarySha256: string; plannerUserId: number; state: string; createdAt: string }>;
  repairs: Array<{
    repairCommandId: string;
    ownerCode: string;
    actionCode: string;
    state: string;
    ownerResultReference?: string;
    ownerResultSha256?: string;
    requestedAt: string;
    observedAt?: string;
  }>;
  reviews: Array<{ reviewId: string; reviewerUserId: number; decision: string; reasonSha256: string; reviewedAt: string }>;
  states: Array<{ stateEventId: string; fromState?: string; toState: string; actorUserId: number; occurredAt: string }>;
  audits: Array<{ auditId: string; actionCode: string; resultCode: string; correlationId: string; actorUserId: number; occurredAt: string }>;
}
