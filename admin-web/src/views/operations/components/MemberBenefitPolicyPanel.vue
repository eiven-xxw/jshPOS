<template>
  <div class="member-benefit-panel">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="会员等级权益与会员价（默认关闭）"
      description="页面只提交版本和审批意图；会员价、促销组合、成交分摊与退款恢复全部由各 Owner 服务端计算。"
    />

    <el-row :gutter="16" class="mt-3">
      <el-col :lg="12" :xs="24">
        <el-card shadow="never" header="1. Member Owner 权益版本">
          <el-form :model="policy" label-width="108px">
            <el-form-item label="策略 / 版本">
              <el-input v-model="policy.policyId" class="id-input" />
              <el-input v-model="policy.versionId" class="id-input ml-2" />
            </el-form-item>
            <el-form-item label="编码 / 名称">
              <el-input v-model="policy.policyCode" class="medium-input" />
              <el-input v-model="policy.displayName" class="medium-input ml-2" />
            </el-form-item>
            <el-form-item label="适用门店"><el-input v-model="policy.storeId" class="small-input" /></el-form-item>
            <el-form-item label="等级">
              <el-input v-model="policy.levelCode" class="small-input" />
              <el-checkbox v-model="policy.memberPriceEligible" class="ml-2">会员价</el-checkbox>
              <el-checkbox v-model="policy.stackingAllowed">允许叠加</el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['member:benefit:create']" type="primary" @click="createPolicy">创建草稿</el-button>
              <el-button :disabled="!policyResult" @click="policyAction('validate')">预检</el-button>
              <el-button v-hasPermi="['member:benefit:approve']" :disabled="policyResult?.state !== 'VALIDATED'" @click="policyAction('approve')"
                >审批</el-button
              >
              <el-button
                v-hasPermi="['member:benefit:publish']"
                type="success"
                :disabled="policyResult?.state !== 'APPROVED'"
                @click="policyAction('publish')"
                >发布</el-button
              >
            </el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['member:benefit:pause']" :disabled="policyResult?.state !== 'PUBLISHED'" @click="policyAction('pause')"
                >暂停</el-button
              >
              <el-button v-hasPermi="['member:benefit:publish']" :disabled="policyResult?.state !== 'PAUSED'" @click="policyAction('resume')"
                >恢复</el-button
              >
              <el-button v-hasPermi="['member:benefit:revoke']" type="danger" :disabled="!policyResult" @click="policyAction('revoke')"
                >撤回</el-button
              >
            </el-form-item>
          </el-form>
          <el-descriptions v-if="policyResult" :column="2" border>
            <el-descriptions-item label="状态">{{ policyResult.state }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ policyResult.version }}</el-descriptions-item>
            <el-descriptions-item label="组合策略">{{ policyResult.defaultCombinationPolicy }}</el-descriptions-item>
            <el-descriptions-item label="摘要">{{ shortHash(policyResult.contentSha256) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :lg="12" :xs="24">
        <el-card shadow="never" header="2. Pricing Owner 会员价版本">
          <el-form :model="price" label-width="108px">
            <el-form-item label="版本 ULID"><el-input v-model="price.versionId" class="id-input" /></el-form-item>
            <el-form-item label="价格簿 / 版本">
              <el-input v-model="price.bookCode" class="medium-input" />
              <el-input-number v-model="price.versionNo" :min="1" :precision="0" class="small-input ml-2" />
            </el-form-item>
            <el-form-item label="门店 / 等级">
              <el-input v-model="price.storeId" class="small-input" />
              <el-input v-model="price.levelCode" class="small-input ml-2" />
            </el-form-item>
            <el-form-item label="SKU / 单位">
              <el-input v-model="price.skuId" class="small-input" />
              <el-input v-model="price.unitId" class="small-input ml-2" />
            </el-form-item>
            <el-form-item label="会员价（分）"><el-input-number v-model="price.amountMinor" :min="0" :precision="0" /></el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['pricing:member-price:publish']" type="primary" @click="createPrice">创建草稿</el-button>
              <el-button :disabled="!priceResult" @click="priceAction('validate')">预检</el-button>
              <el-button :disabled="priceResult?.state !== 'VALIDATED'" @click="priceAction('approve')">审批</el-button>
              <el-button type="success" :disabled="priceResult?.state !== 'APPROVED'" @click="priceAction('publish')">发布</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="priceResult" :column="2" border>
            <el-descriptions-item label="状态">{{ priceResult.state }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ priceResult.versionNo }}</el-descriptions-item>
            <el-descriptions-item label="门店">{{ priceResult.storeId || '租户范围' }}</el-descriptions-item>
            <el-descriptions-item label="摘要">{{ shortHash(priceResult.contentSha256) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="mt-3" shadow="never" header="3. 无 PII 离线权益包">
      <el-form :inline="true" :model="packageForm">
        <el-form-item label="门店"><el-input v-model="packageForm.storeId" class="small-input" /></el-form-item>
        <el-form-item label="上一版本"><el-input-number v-model="packageForm.previousVersion" :min="0" :precision="0" /></el-form-item>
        <el-form-item label="新版本"><el-input-number v-model="packageForm.packageVersion" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="过期时间"
          ><el-date-picker v-model="packageForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]"
        /></el-form-item>
        <el-form-item>
          <el-button
            v-hasPermi="['promotion:rule:publish']"
            type="primary"
            :disabled="policyResult?.state !== 'PUBLISHED' || priceResult?.state !== 'PUBLISHED'"
            @click="publishPackage"
            >签名发布</el-button
          >
        </el-form-item>
      </el-form>
      <el-descriptions v-if="packageResult" :column="4" border>
        <el-descriptions-item label="包版本">{{ packageResult.packageVersion }}</el-descriptions-item>
        <el-descriptions-item label="权益 / 会员价">{{ packageResult.benefitCount }} / {{ packageResult.memberPriceCount }}</el-descriptions-item>
        <el-descriptions-item label="签名密钥">{{ packageResult.signingKeyId }}</el-descriptions-item>
        <el-descriptions-item label="摘要">{{ shortHash(packageResult.payloadSha256) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {
  createBenefitPolicy,
  createMemberPriceVersion,
  publishMemberBenefitPackage,
  transitionBenefitPolicy,
  transitionMemberPrice
} from '@/api/member-benefit';
import type { BenefitPolicyVersionVO, MemberBenefitPackageVO, MemberPriceVersionVO } from '@/api/member-benefit/types';
import { newOperationCommandId } from '@/api/operations';

const policy = reactive({
  policyId: newOperationCommandId(),
  versionId: newOperationCommandId(),
  policyCode: 'V1_MEMBER_BENEFIT',
  displayName: '商业 V1 会员等级权益',
  storeId: '1101',
  levelCode: 'GOLD',
  memberPriceEligible: true,
  stackingAllowed: false
});
const price = reactive({
  versionId: newOperationCommandId(),
  bookCode: 'V1_MEMBER_PRICE',
  versionNo: 1,
  storeId: '1101',
  levelCode: 'GOLD',
  skuId: '101',
  unitId: '1001',
  amountMinor: 580
});
const packageForm = reactive({
  storeId: '1101',
  previousVersion: 0,
  packageVersion: 1,
  expiresAt: new Date(Date.now() + 7 * 86400_000).toISOString()
});
const policyResult = ref<BenefitPolicyVersionVO>();
const priceResult = ref<MemberPriceVersionVO>();
const packageResult = ref<MemberBenefitPackageVO>();

const shortHash = (value: string) => `${value.slice(0, 12)}…`;
const identity = () => newOperationCommandId();
const expiry = () => new Date(Date.now() + 30 * 86400_000).toISOString();

const createPolicy = async () => {
  const command = identity();
  policyResult.value = (
    await createBenefitPolicy({
      commandId: command,
      policyId: policy.policyId,
      versionId: policy.versionId,
      policyCode: policy.policyCode,
      displayName: policy.displayName,
      levelRules: [{ levelCode: policy.levelCode, memberPriceEligible: policy.memberPriceEligible, stackingAllowed: policy.stackingAllowed }],
      storeIds: [Number(policy.storeId)],
      correlationId: command
    })
  ).data;
};

const policyAction = async (action: 'validate' | 'approve' | 'publish' | 'pause' | 'resume' | 'revoke') => {
  if (!policyResult.value) return;
  await ElMessageBox.confirm(`确认执行权益版本 ${action}？该操作由服务端状态机和审计约束。`, '权益版本操作', { type: 'warning' });
  const command = identity();
  policyResult.value = (
    await transitionBenefitPolicy(policy.policyId, policy.versionId, action, {
      commandId: command,
      contentSha256: policyResult.value.contentSha256,
      effectiveAt: action === 'publish' ? new Date().toISOString() : undefined,
      expiresAt: action === 'publish' ? expiry() : undefined,
      reasonCode: action === 'revoke' ? 'OPERATOR_REVOKED' : undefined,
      reason: `运营工作台执行 ${action}`,
      correlationId: command
    })
  ).data;
};

const createPrice = async () => {
  const command = identity();
  priceResult.value = (
    await createMemberPriceVersion({
      commandId: command,
      versionId: price.versionId,
      bookCode: price.bookCode,
      versionNo: price.versionNo,
      storeId: Number(price.storeId),
      items: [
        { itemId: identity(), levelCode: price.levelCode, skuId: Number(price.skuId), unitId: Number(price.unitId), amountMinor: price.amountMinor }
      ],
      correlationId: command
    })
  ).data;
};

const priceAction = async (action: 'validate' | 'approve' | 'publish') => {
  if (!priceResult.value) return;
  await ElMessageBox.confirm(`确认执行会员价版本 ${action}？页面不会计算成交价。`, '会员价版本操作', { type: 'warning' });
  const command = identity();
  priceResult.value = (
    await transitionMemberPrice(price.versionId, action, {
      commandId: command,
      contentSha256: priceResult.value.contentSha256,
      effectiveAt: action === 'publish' ? new Date().toISOString() : undefined,
      expiresAt: action === 'publish' ? expiry() : undefined,
      correlationId: command
    })
  ).data;
};

const publishPackage = async () => {
  await ElMessageBox.confirm('确认由服务端读取已发布权益与会员价并生成无 PII 签名包？', '发布离线权益包', { type: 'warning' });
  const command = identity();
  packageResult.value = (await publishMemberBenefitPackage({ ...packageForm, correlationId: command })).data;
};
</script>

<style scoped>
.id-input {
  width: 270px;
}
.medium-input {
  width: 220px;
}
.small-input {
  width: 135px;
}
</style>
