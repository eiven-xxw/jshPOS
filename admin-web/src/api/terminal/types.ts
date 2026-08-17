/** Gate 6A 终端登记只读视图，不包含任何秘密摘要。 */
export interface TerminalVO {
  deviceId: string;
  orgUnitId: number;
  storeId: number;
  terminalId: string;
  boundUserId: number;
  status: 'ACTIVE' | 'BLOCKED' | 'REVOKED' | 'RETIRED';
  terminalProfileCode: string;
  appVersion: string;
  minProtocolVersion: string;
  maxProtocolVersion: string;
  schemaVersion: string;
  capabilitySha256?: string;
  credentialVersion: number;
  evidenceLevel: 'LEGACY_IMPORTED' | 'SYNTHETIC' | 'REAL_DEVICE';
  recordVersion: number;
  activatedAt?: string;
  lastSeenAt?: string;
}

export interface TerminalPageVO {
  items: TerminalVO[];
  total: number;
  page: number;
  size: number;
}

export interface IssueActivationRequest {
  orgUnitId: number;
  storeId: number;
  boundUserId: number;
  terminalProfileCode: string;
  expiresInSeconds: number;
  idempotencyKey: string;
}

export interface IssuedActivationVO {
  activationId: string;
  activationSecret?: string;
  expiresAt: string;
  status: string;
  secretShownOnce: boolean;
}

export interface ChangeTerminalStatusRequest {
  targetStatus: 'ACTIVE' | 'BLOCKED' | 'REVOKED' | 'RETIRED';
  reason: string;
  idempotencyKey: string;
  expectedVersion: number;
}

export interface RotatedCredentialVO {
  deviceId: string;
  credentialVersion: number;
  deviceCredential?: string;
  secretShownOnce: boolean;
}
