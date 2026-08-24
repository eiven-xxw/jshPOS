<template>
  <div class="operation-panel">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="只允许合成会员与既有促销规则"
      description="促销金额由 Promotion Owner 计算；会员身份只允许虚构 MEMBER_CODE，不得在本 Sprint 输入真实手机号、证件或其他 PII。"
    />
    <OwnerPageFeedback surface-id="VUE-06" :state="pageState" :failure="pageFailure" @retry="reloadCurrent" />
    <el-tabs class="mt-3" type="border-card">
      <el-tab-pane label="促销规则生命周期">
        <el-form label-width="115px" class="form-grid">
          <el-form-item label="规则 / 版本 ULID">
            <el-input v-model="promotion.ruleId" class="id-input" /> / <el-input v-model="promotion.ruleVersionId" class="id-input" />
          </el-form-item>
          <el-form-item label="编码 / 名称">
            <el-input v-model="promotion.ruleCode" class="medium-input" /> / <el-input v-model="promotion.name" class="medium-input" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="promotion.ruleType" class="medium-input">
              <el-option label="单品直减" value="AMOUNT_OFF" />
              <el-option label="单品折扣" value="PERCENT_OFF" />
              <el-option label="满额减" value="THRESHOLD_AMOUNT_OFF" />
              <el-option label="满件减" value="THRESHOLD_QUANTITY_OFF" />
            </el-select>
          </el-form-item>
          <el-form-item label="门店 / SKU">
            <el-input v-model="promotion.storeId" class="small-input" /> / <el-input v-model="promotion.skuId" class="small-input" />
          </el-form-item>
          <el-form-item label="优惠参数">
            <el-input v-model.number="promotion.amountMinor" class="small-input" placeholder="优惠分" />
            <el-input v-model="promotion.discountRate" class="small-input ml-2" placeholder="折扣率" />
            <el-input v-model.number="promotion.thresholdMinor" class="small-input ml-2" placeholder="门槛分" />
          </el-form-item>
          <el-form-item label="生效时间"
            ><el-date-picker v-model="promotion.effectiveFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"
          /></el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['promotion:rule:create']" type="primary" @click="createRule">创建规则</el-button>
            <el-button v-hasPermi="['promotion:rule:validate']" @click="ruleAction('validate')">预检</el-button>
            <el-button v-hasPermi="['promotion:rule:approve']" type="warning" @click="ruleAction('approve')">审批</el-button>
            <el-button v-hasPermi="['promotion:rule:publish']" type="danger" @click="ruleAction('publish')">发布</el-button>
            <el-button v-hasPermi="['promotion:rule:publish']" @click="ruleAction('pause')">暂停</el-button>
            <el-button v-hasPermi="['promotion:rule:approve']" @click="ruleAction('reject')">驳回</el-button>
            <el-button v-hasPermi="['promotion:rule:publish']" @click="ruleAction('retire')">退役</el-button>
          </el-form-item>
        </el-form>
        <el-descriptions v-if="promotionResult" :column="4" border>
          <el-descriptions-item label="服务端状态">{{ promotionResult.state }}</el-descriptions-item>
          <el-descriptions-item label="乐观锁版本">{{ promotionResult.version }}</el-descriptions-item>
          <el-descriptions-item label="规则版本号">{{ promotionResult.versionNo }}</el-descriptions-item>
          <el-descriptions-item label="内容摘要">{{ promotionResult.contentSha256 }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <el-tab-pane label="会员隐私与积分">
        <el-form :inline="true">
          <el-form-item label="门店 ID"><el-input v-model="member.storeId" class="small-input" /></el-form-item>
          <el-form-item label="虚构会员码"><el-input v-model="member.identityValue" class="medium-input" show-password /></el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['member:profile:create']" type="primary" @click="createSyntheticMember">创建虚构会员</el-button>
            <el-button v-hasPermi="['member:profile:read']" data-testid="member-resolve" :disabled="submitting" @click="resolveSyntheticMember"
              >安全解析</el-button
            >
          </el-form-item>
        </el-form>
        <el-descriptions v-if="memberResult" :column="4" border>
          <el-descriptions-item label="会员 ID">{{ memberResult.member.memberId }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ memberResult.member.state }}</el-descriptions-item>
          <el-descriptions-item label="脱敏显示">{{ memberResult.member.displayName }}</el-descriptions-item>
          <el-descriptions-item label="匹配身份">{{ memberResult.matchedIdentity.maskedValue }}</el-descriptions-item>
        </el-descriptions>
        <el-form v-if="activeMemberId" :inline="true" class="mt-3">
          <el-form-item label="同意状态">
            <el-select v-model="member.consentState" class="small-input"
              ><el-option label="同意" value="GRANTED" /><el-option label="撤回" value="REVOKED"
            /></el-select>
          </el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['member:consent:record']" @click="recordConsent">记录同意</el-button>
          </el-form-item>
          <el-form-item label="隐私请求">
            <el-select v-model="member.privacyType" class="small-input">
              <el-option label="访问" value="ACCESS" /><el-option label="导出" value="EXPORT" /> <el-option label="更正" value="CORRECT" /><el-option
                label="删除"
                value="DELETE"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['member:privacy:request']" type="warning" @click="requestPrivacy">提交隐私请求</el-button>
          </el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['member:points:read']" @click="loadPoints">查询积分</el-button>
          </el-form-item>
        </el-form>
        <el-descriptions v-if="points" :column="4" border class="mt-3">
          <el-descriptions-item label="可用">{{ points.availablePoints }}</el-descriptions-item>
          <el-descriptions-item label="冻结">{{ points.frozenPoints }}</el-descriptions-item>
          <el-descriptions-item label="债务">{{ points.debtPoints }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ points.version }}</el-descriptions-item>
        </el-descriptions>
        <el-form v-if="points" :inline="true" class="mt-3">
          <el-form-item label="调整数量"><el-input v-model="member.pointsDelta" class="small-input" /></el-form-item>
          <el-form-item label="审批人 ID"><el-input v-model="member.approvalUserId" class="small-input" /></el-form-item>
          <el-form-item><el-button v-hasPermi="['member:points:adjust']" type="danger" @click="adjustPoints">双人审批调整</el-button></el-form-item>
        </el-form>
        <el-descriptions v-if="memberWriteResult" :column="3" border class="mt-3">
          <el-descriptions-item label="最近写入状态">{{ memberWriteResult.status || memberWriteResult.state || 'APPENDED' }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ memberWriteResult.version }}</el-descriptions-item>
          <el-descriptions-item label="关联标识">{{ memberWriteResult.correlationId || '-' }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>
      <el-tab-pane label="会员等级权益与会员价">
        <MemberBenefitPolicyPanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import {
  adjustMemberPoints,
  createMember,
  createPrivacyRequest,
  createPromotionRule,
  getMemberPoints,
  newOperationCommandId,
  recordMemberConsent,
  resolveMember,
  transitionPromotionRule
} from '@/api/operations';
import type { OwnerOperationView, PointsAccountView, ResolvedMemberView, RuleVersionView } from '@/api/operations/types';
import { exactDecimal } from '../model';
import { useControlledOperation } from '../useControlledOperation';
import MemberBenefitPolicyPanel from './MemberBenefitPolicyPanel.vue';
import OwnerPageFeedback from './OwnerPageFeedback.vue';

const { pageState, pageFailure, submitting, runRead, runControlled } = useControlledOperation();
const promotion = reactive({
  ruleId: newOperationCommandId(),
  ruleVersionId: newOperationCommandId(),
  ruleCode: 'SYN_PROMO_001',
  name: '虚构便利店单品直减',
  ruleType: 'AMOUNT_OFF',
  storeId: '1101',
  skuId: '101',
  amountMinor: 100,
  discountRate: '0.9',
  thresholdMinor: 1000,
  effectiveFrom: new Date(Date.now() + 3600_000).toISOString()
});
const promotionResult = ref<RuleVersionView>();
const member = reactive({
  memberId: newOperationCommandId(),
  identityId: newOperationCommandId(),
  consentId: newOperationCommandId(),
  privacyRequestId: newOperationCommandId(),
  pointsLedgerId: newOperationCommandId(),
  approvalRef: newOperationCommandId(),
  pointsOccurredAt: new Date().toISOString(),
  storeId: '1101',
  identityValue: 'SYN-MEMBER-0001',
  consentState: 'GRANTED' as 'GRANTED' | 'REVOKED',
  privacyType: 'ACCESS' as 'ACCESS' | 'EXPORT' | 'CORRECT' | 'DELETE',
  pointsDelta: '10.000000',
  approvalUserId: '9001'
});
const memberResult = ref<ResolvedMemberView>();
const memberWriteResult = ref<OwnerOperationView>();
const points = ref<PointsAccountView>();
const activeMemberId = computed(() => memberResult.value?.member.memberId || (memberWriteResult.value?.memberId as string | undefined));
const lastReadMode = ref<'member' | 'points'>('member');

const createRule = async () => {
  const benefit =
    promotion.ruleType === 'PERCENT_OFF'
      ? { discountRate: promotion.discountRate, bundleComponents: [] }
      : promotion.ruleType === 'THRESHOLD_AMOUNT_OFF'
        ? { amountMinor: promotion.amountMinor, thresholdMinor: promotion.thresholdMinor, bundleComponents: [] }
        : promotion.ruleType === 'THRESHOLD_QUANTITY_OFF'
          ? { amountMinor: promotion.amountMinor, thresholdQuantity: '3.000000', bundleComponents: [] }
          : { amountMinor: promotion.amountMinor, bundleComponents: [] };
  const changed = await runControlled({
    owner: 'Promotion',
    objectId: promotion.ruleVersionId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE_RULE_VERSION',
    impact: '创建版本化规则草稿；前端不执行促销计算',
    reason: '运营录入已批准范围内的基础促销',
    execute: (key) =>
      createPromotionRule({
        commandId: key,
        ruleId: promotion.ruleId,
        ruleVersionId: promotion.ruleVersionId,
        ruleCode: promotion.ruleCode,
        name: promotion.name,
        definition: {
          ruleType: promotion.ruleType,
          priority: 100,
          stackMode: 'STACKABLE',
          effectiveFrom: promotion.effectiveFrom,
          scope: {
            skuIds: [promotion.skuId],
            categoryIds: [],
            brandIds: [],
            storeIds: [promotion.storeId],
            channels: ['POS'],
            businessDays: [1, 2, 3, 4, 5, 6, 7]
          },
          benefit
        },
        correlationId: key
      })
  });
  if (changed) promotionResult.value = changed;
};

const ruleAction = async (action: 'validate' | 'approve' | 'publish' | 'pause' | 'reject' | 'retire') => {
  if (!promotionResult.value) return ElMessage.warning('请先创建并取得服务端规则状态与版本');
  const changed = await runControlled({
    owner: 'Promotion',
    objectId: promotion.ruleVersionId,
    currentState: promotionResult.value.state,
    currentVersion: promotionResult.value.version,
    action: action.toUpperCase(),
    impact: '推进不可变规则版本生命周期，不在前端重算优惠',
    reason: '促销运营职责链复核通过',
    execute: (key) =>
      transitionPromotionRule(promotion.ruleId, promotion.ruleVersionId, action, {
        commandId: key,
        expectedVersion: promotionResult.value!.version,
        reason: '受控促销版本操作',
        correlationId: key
      })
  });
  if (changed) promotionResult.value = changed;
};

const createSyntheticMember = async () => {
  if (!member.identityValue.startsWith('SYN-')) return ElMessage.error('本 Sprint 只允许 SYN- 前缀虚构会员码');
  const changed = await runControlled({
    owner: 'Member',
    objectId: member.memberId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE_SYNTHETIC_MEMBER',
    impact: '创建虚构会员和令牌化身份，不保存真实 PII',
    reason: '内部合成验收创建虚构主体',
    execute: (key) =>
      createMember({
        commandId: key,
        memberId: member.memberId,
        identityId: member.identityId,
        identityType: 'MEMBER_CODE',
        identityValue: member.identityValue,
        correlationId: key
      })
  });
  if (changed) memberWriteResult.value = changed;
};

const resolveSyntheticMember = async () => {
  if (!member.identityValue.startsWith('SYN-')) return ElMessage.error('本 Sprint 禁止输入真实 PII');
  lastReadMode.value = 'member';
  const result = await runRead(() =>
    resolveMember({ storeId: Number(member.storeId), identityType: 'MEMBER_CODE', identityValue: member.identityValue })
  );
  if (result) memberResult.value = result;
};

const recordConsent = async () => {
  if (!activeMemberId.value) return;
  const changed = await runControlled({
    owner: 'Member.Consent',
    objectId: activeMemberId.value,
    currentState: member.consentState === 'GRANTED' ? 'NOT_RECORDED' : 'GRANTED',
    currentVersion: memberResult.value?.member.version || 0,
    action: member.consentState,
    impact: '追加同意或撤回事实，不改写历史记录',
    reason: '根据虚构主体明确意愿记录',
    execute: (key) =>
      recordMemberConsent(activeMemberId.value!, {
        commandId: key,
        consentId: member.consentId,
        purposeCode: 'LOYALTY_PROGRAM',
        policyVersion: 'synthetic-v1',
        state: member.consentState,
        evidenceSha256: 'a'.repeat(64),
        correlationId: key
      })
  });
  if (changed) memberWriteResult.value = changed;
};

const requestPrivacy = async () => {
  if (!activeMemberId.value) return;
  const changed = await runControlled({
    owner: 'Member.Privacy',
    objectId: member.privacyRequestId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: member.privacyType,
    impact: '追加隐私请求并进入受审计处理流程，不删除交易事实',
    reason: '虚构主体隐私权利演练',
    execute: (key) =>
      createPrivacyRequest(activeMemberId.value!, {
        commandId: key,
        requestId: member.privacyRequestId,
        requestType: member.privacyType,
        reason: '内部合成隐私请求',
        correlationId: key
      })
  });
  if (changed) memberWriteResult.value = changed;
};

const loadPoints = async () => {
  if (!activeMemberId.value) return;
  lastReadMode.value = 'points';
  const result = await runRead(() => getMemberPoints(activeMemberId.value!, member.storeId));
  if (result) points.value = result;
};

const reloadCurrent = () => (lastReadMode.value === 'points' ? loadPoints() : resolveSyntheticMember());

const adjustPoints = async () => {
  if (!points.value || !activeMemberId.value) return ElMessage.warning('请先查询积分服务端版本');
  const changed = await runControlled({
    owner: 'Member.Points',
    objectId: activeMemberId.value,
    currentState: 'PROJECTED',
    currentVersion: points.value.version,
    action: 'MANUAL_ADJUST',
    impact: '追加积分调整流水并重建投影，禁止覆盖余额',
    reason: '双人审批的内部合成积分调整',
    execute: (key) =>
      adjustMemberPoints(activeMemberId.value!, {
        commandId: key,
        ledgerId: member.pointsLedgerId,
        storeId: Number(member.storeId),
        signedAmount: exactDecimal(member.pointsDelta, true),
        policyVersion: 'synthetic-points-v1',
        reason: '内部合成双人审批调整',
        approvalUserId: Number(member.approvalUserId),
        approvalRef: member.approvalRef,
        occurredAt: member.pointsOccurredAt,
        correlationId: key
      })
  });
  if (changed) memberWriteResult.value = changed;
  await loadPoints();
};
</script>

<style scoped>
.form-grid {
  max-width: 1100px;
}
.id-input {
  width: 280px;
}
.medium-input {
  width: 220px;
}
.small-input {
  width: 130px;
}
</style>
