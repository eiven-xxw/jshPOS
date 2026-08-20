<template>
  <div class="operations-home">
    <section class="hero-panel">
      <div>
        <p class="eyebrow">鲸熵汇收银系统</p>
        <h1>经营工作台</h1>
        <p class="hero-copy">当前数据仅来自可信登录会话可见范围；工作台只读汇总，不反向修改任何业务事实。</p>
      </div>
      <div class="hero-actions">
        <el-button :loading="loading" icon="Refresh" @click="loadDashboard">刷新数据</el-button>
        <el-button type="primary" icon="Goods" @click="openModule('/catalog')">商品价格</el-button>
        <el-button type="success" icon="Shop" @click="openModule('/foundation')">组织门店</el-button>
      </div>
    </section>

    <el-alert
      v-if="loadWarning"
      class="mb-4"
      type="warning"
      :title="loadWarning"
      description="各卡片独立加载；失败模块保持未知，不以 0 伪装为正常。"
      :closable="false"
      show-icon
    />

    <el-row :gutter="16" class="summary-grid" v-loading="loading">
      <el-col v-for="card in summaryCards" :key="card.key" :xs="12" :sm="12" :md="6">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-icon" :class="`tone-${card.tone}`">
            <el-icon><component :is="card.icon" /></el-icon>
          </div>
          <div>
            <div class="summary-label">{{ card.label }}</div>
            <div class="summary-value">{{ card.available ? card.value : '—' }}</div>
            <div class="summary-note">{{ card.note }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt-4">
      <el-col :xs="24" :lg="15">
        <el-card shadow="never" class="workspace-card">
          <template #header>
            <div class="card-heading">
              <div>
                <strong>核心运营入口</strong>
                <span>所有操作继续由正式 API、后端权限与 Owner 审计控制</span>
              </div>
            </div>
          </template>
          <div class="module-grid">
            <button v-for="module in modules" :key="module.path" class="module-tile" type="button" @click="openModule(module.path)">
              <span class="module-icon"
                ><el-icon><component :is="module.icon" /></el-icon
              ></span>
              <span class="module-content">
                <strong>{{ module.title }}</strong>
                <small>{{ module.description }}</small>
              </span>
              <el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="9">
        <el-card shadow="never" class="workspace-card attention-card">
          <template #header>
            <div class="card-heading">
              <div><strong>运营关注</strong><span>只读提示，不替代业务审批或对账</span></div>
            </div>
          </template>
          <el-empty v-if="attentionItems.length === 0 && !loading" description="当前没有可见关注项" :image-size="84" />
          <div v-else class="attention-list">
            <button v-for="item in attentionItems" :key="item.key" type="button" @click="openModule(item.path)">
              <el-tag :type="item.type" effect="light">{{ item.value }}</el-tag>
              <span
                ><strong>{{ item.title }}</strong
                ><small>{{ item.description }}</small></span
              >
              <el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="workspace-card mt-4">
      <template #header>
        <div class="card-heading audit-heading">
          <div><strong>最近关键审计</strong><span>按可信租户和数据范围返回，页面不接收 tenant_id 参数</span></div>
          <el-button link type="primary" @click="openModule('/foundation')">查看平台基础</el-button>
        </div>
      </template>
      <el-table :data="recentAudits" row-key="auditId" empty-text="暂无可见审计记录">
        <el-table-column prop="occurredAt" label="时间" min-width="180" />
        <el-table-column prop="actionCode" label="动作" min-width="190" show-overflow-tooltip />
        <el-table-column prop="targetType" label="对象" min-width="130" />
        <el-table-column prop="correlationId" label="关联标识" min-width="220" show-overflow-tooltip />
        <el-table-column prop="result" label="结果" width="110">
          <template #default="scope">
            <el-tag :type="scope.row.result === 'SUCCESS' ? 'success' : scope.row.result === 'DENIED' ? 'warning' : 'danger'">
              {{ scope.row.result }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <p class="evidence-boundary">内部产品化软件版本：真实支付、真实外设、现场试点和完整 Alpha UAT 尚未开放。</p>
  </div>
</template>

<script setup name="OperationsHome" lang="ts">
import { listProducts } from '@/api/catalog';
import type { ProductVO } from '@/api/catalog/types';
import { listAuditEvents, listConfigTemplates, listOrgUnits, listStores } from '@/api/foundation';
import type { AuditEventVO, ConfigTemplateVO, OrgUnitVO, StoreVO } from '@/api/foundation/types';
import { buildOperationsSummary } from '@/views/operations/model';
import { DataAnalysis, Goods, Lock, Monitor, OfficeBuilding, SetUp, Shop, UserFilled } from '@element-plus/icons-vue';
import type { Component } from 'vue';

interface SummaryCard {
  key: string;
  label: string;
  value: number;
  available: boolean;
  note: string;
  icon: Component;
  tone: 'green' | 'blue' | 'amber' | 'violet';
}

const router = useRouter();
const loading = ref(false);
const loadWarning = ref('');
const orgUnits = ref<OrgUnitVO[]>([]);
const stores = ref<StoreVO[]>([]);
const products = ref<ProductVO[]>([]);
const templates = ref<ConfigTemplateVO[]>([]);
const recentAudits = ref<AuditEventVO[]>([]);
const available = reactive({ org: false, store: false, product: false, config: false, audit: false });

const summary = computed(() =>
  buildOperationsSummary({
    orgStatuses: orgUnits.value.map((item) => item.status),
    storeStatuses: stores.value.map((item) => item.status),
    productStatuses: products.value.map((item) => item.status),
    configStatuses: templates.value.map((item) => item.status),
    auditResults: recentAudits.value.map((item) => item.result)
  })
);

const summaryCards = computed<SummaryCard[]>(() => [
  {
    key: 'org',
    label: '可见组织',
    value: summary.value.orgCount,
    available: available.org,
    note: '总部 / 区域 / 公司',
    icon: OfficeBuilding,
    tone: 'blue'
  },
  {
    key: 'store',
    label: '可见门店',
    value: summary.value.storeCount,
    available: available.store,
    note: '业务日与行业模板',
    icon: Shop,
    tone: 'green'
  },
  {
    key: 'product',
    label: '商品 SKU',
    value: summary.value.productCount,
    available: available.product,
    note: '条码 / 多单位 / 状态',
    icon: Goods,
    tone: 'amber'
  },
  {
    key: 'config',
    label: '行业模板',
    value: summary.value.configCount,
    available: available.config,
    note: '便利店 / 零食折扣 / 社区超市',
    icon: SetUp,
    tone: 'violet'
  }
]);

const modules = [
  { title: '组织与门店', description: '组织树、门店、时区、业务日和行业模板', path: '/foundation', icon: Shop },
  { title: '员工与角色', description: '员工账号、角色权限和组织门店数据范围', path: '/system/user', icon: UserFilled },
  { title: '角色权限', description: '功能权限、数据范围与职责分离', path: '/system/role', icon: Lock },
  { title: '商品价格', description: '商品、条码、多单位、分类品牌、价格版本和导入', path: '/catalog', icon: Goods },
  { title: '经营报表', description: '销售、收银、库存、成本与内部对账投影', path: '/reporting', icon: DataAnalysis },
  { title: '终端登记', description: '终端激活、门店绑定、能力与吊销状态', path: '/terminal-registry', icon: Monitor }
];

const attentionItems = computed(() => [
  ...(available.store && summary.value.preparingStoreCount > 0
    ? [
        {
          key: 'store',
          title: '待启用门店',
          description: '仍处于 PREPARING',
          value: summary.value.preparingStoreCount,
          type: 'warning' as const,
          path: '/foundation'
        }
      ]
    : []),
  ...(available.product && summary.value.inactiveProductCount > 0
    ? [
        {
          key: 'product',
          title: '未启用商品',
          description: '草稿或停用 SKU',
          value: summary.value.inactiveProductCount,
          type: 'info' as const,
          path: '/catalog'
        }
      ]
    : []),
  ...(available.audit && summary.value.deniedAuditCount > 0
    ? [
        {
          key: 'audit',
          title: '失败或拒绝操作',
          description: '请按关联标识复核审计',
          value: summary.value.deniedAuditCount,
          type: 'danger' as const,
          path: '/foundation'
        }
      ]
    : [])
]);

const loadDashboard = async () => {
  loading.value = true;
  loadWarning.value = '';
  const results = await Promise.allSettled([
    listOrgUnits(),
    listStores(),
    listProducts(undefined, 100),
    listConfigTemplates(),
    listAuditEvents(undefined, 20)
  ]);
  const [orgResult, storeResult, productResult, configResult, auditResult] = results;
  if (orgResult.status === 'fulfilled') {
    orgUnits.value = orgResult.value.data;
    available.org = true;
  }
  if (storeResult.status === 'fulfilled') {
    stores.value = storeResult.value.data;
    available.store = true;
  }
  if (productResult.status === 'fulfilled') {
    products.value = productResult.value.data;
    available.product = true;
  }
  if (configResult.status === 'fulfilled') {
    templates.value = configResult.value.data;
    available.config = true;
  }
  if (auditResult.status === 'fulfilled') {
    recentAudits.value = auditResult.value.data;
    available.audit = true;
  }
  const failedCount = results.filter((result) => result.status === 'rejected').length;
  loadWarning.value = failedCount > 0 ? `${failedCount} 个数据模块暂时不可用` : '';
  loading.value = false;
};

const openModule = async (path: string) => {
  try {
    await router.push(path);
  } catch {
    ElMessage.warning('当前角色尚未获得该模块的菜单权限');
  }
};

onMounted(loadDashboard);
</script>

<style lang="scss" scoped>
.operations-home {
  min-height: 100%;
  padding: 22px;
  background: #f4f7f6;
  color: #173b35;
}

.hero-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 28px 30px;
  margin-bottom: 18px;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(125deg, #075f50 0%, #0d7a65 55%, #124f78 100%);
  border-radius: 18px;
  box-shadow: 0 14px 36px rgb(10 79 67 / 18%);

  .eyebrow {
    margin: 0 0 6px;
    font-size: 13px;
    font-weight: 700;
    letter-spacing: 0.16em;
    opacity: 0.78;
  }
  h1 {
    margin: 0;
    font-size: 30px;
    line-height: 1.2;
  }
  .hero-copy {
    max-width: 680px;
    margin: 10px 0 0;
    color: rgb(255 255 255 / 82%);
  }
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}
.summary-grid :deep(.el-col) {
  margin-bottom: 12px;
}
.summary-card :deep(.el-card__body) {
  display: flex;
  gap: 16px;
  align-items: center;
  min-height: 116px;
}
.summary-icon {
  display: grid;
  flex: 0 0 52px;
  width: 52px;
  height: 52px;
  font-size: 24px;
  border-radius: 15px;
  place-items: center;
}
.tone-green {
  color: #08765f;
  background: #d9f3eb;
}
.tone-blue {
  color: #2364a8;
  background: #e1edfb;
}
.tone-amber {
  color: #a35c08;
  background: #fff0d8;
}
.tone-violet {
  color: #7047a8;
  background: #eee6fa;
}
.summary-label {
  color: #60736f;
}
.summary-value {
  margin-top: 2px;
  font-size: 30px;
  font-weight: 760;
  line-height: 1.1;
}
.summary-note {
  margin-top: 6px;
  color: #8a9996;
  font-size: 12px;
}
.workspace-card {
  border: 1px solid #e6ecea;
  border-radius: 14px;
}
.card-heading,
.audit-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.card-heading div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.card-heading strong {
  font-size: 17px;
}
.card-heading span {
  color: #84928f;
  font-size: 12px;
}
.module-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.module-tile,
.attention-list button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 12px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e7eceb;
  border-radius: 12px;
}
.module-tile {
  min-height: 88px;
  padding: 14px;
}
.module-tile:hover,
.attention-list button:hover {
  border-color: #5ca996;
  box-shadow: 0 8px 20px rgb(31 105 88 / 9%);
  transform: translateY(-1px);
}
.module-icon {
  display: grid;
  flex: 0 0 42px;
  width: 42px;
  height: 42px;
  font-size: 20px;
  color: #08715e;
  background: #e3f3ef;
  border-radius: 12px;
  place-items: center;
}
.module-content,
.attention-list button span {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}
.module-content small,
.attention-list small {
  overflow: hidden;
  color: #7b8a87;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attention-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.attention-list button {
  min-height: 68px;
  padding: 12px;
}
.evidence-boundary {
  margin: 18px 0 0;
  color: #7d8c89;
  font-size: 12px;
  text-align: center;
}

@media (max-width: 900px) {
  .hero-panel {
    align-items: flex-start;
    flex-direction: column;
  }
  .hero-actions {
    justify-content: flex-start;
  }
  .module-grid {
    grid-template-columns: 1fr;
  }
}
</style>
