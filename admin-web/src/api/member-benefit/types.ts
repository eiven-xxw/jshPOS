export interface BenefitLevelInput {
  levelCode: string;
  memberPriceEligible: boolean;
  stackingAllowed: boolean;
}

export interface BenefitPolicyVersionVO {
  policyId: string;
  versionId: string;
  policyCode: string;
  displayName: string;
  versionNo: number;
  state: string;
  defaultCombinationPolicy: string;
  allowStacking: boolean;
  memberPriceEligible: boolean;
  effectiveAt?: string;
  expiresAt?: string;
  revocationEpoch: number;
  contentSha256: string;
  version: number;
}

export interface MemberPriceVersionVO {
  versionId: string;
  bookCode: string;
  versionNo: number;
  storeId?: number;
  state: string;
  effectiveAt?: string;
  expiresAt?: string;
  contentSha256: string;
  version: number;
}

export interface MemberBenefitPackageVO {
  packageId: string;
  storeId: number;
  packageVersion: number;
  previousVersion: number;
  payloadSha256: string;
  signingKeyId: string;
  benefitCount: number;
  memberPriceCount: number;
  generatedAt: string;
  expiresAt: string;
}
