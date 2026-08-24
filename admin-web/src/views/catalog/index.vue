<template>
  <div class="p-2">
    <el-alert
      class="mb-3"
      title="商品与价格运营中心"
      description="租户仅由可信登录会话注入；金额由服务端按最小货币单位校验，单位换算使用整数分子分母。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="pageFailure"
      data-testid="catalog-error"
      class="mb-3"
      type="error"
      :closable="false"
      show-icon
      :title="`${pageFailure.message}（${pageFailure.code}）`"
      :description="`关联标识：${pageFailure.correlationId}${pageFailure.operationIdentity ? `；原操作：${pageFailure.operationIdentity}` : ''}`"
    >
      <template #default>
        <span
          >关联标识：{{ pageFailure.correlationId
          }}<template v-if="pageFailure.operationIdentity">；原操作：{{ pageFailure.operationIdentity }}</template></span
        >
        <el-button data-testid="catalog-retry" type="primary" link @click="loadProducts">刷新权威状态</el-button>
      </template>
    </el-alert>
    <el-alert
      v-else-if="pagePhase === 'LOADING'"
      data-testid="catalog-loading"
      class="mb-3"
      type="info"
      :closable="false"
      title="正在加载商品与门店范围…"
    />

    <el-card shadow="hover">
      <template #header>
        <el-space wrap>
          <el-button v-hasPermi="['catalog:product:query']" data-testid="catalog-refresh" icon="Refresh" :disabled="submitting" @click="loadProducts"
            >刷新商品</el-button
          >
          <el-button v-hasPermi="['catalog:definition:manage']" @click="definitionDialog = true">分类 / 品牌 / 单位</el-button>
          <el-button v-hasPermi="['catalog:product:manage']" type="primary" @click="openProductDialog">新增商品</el-button>
          <el-button v-hasPermi="['catalog:import:preflight']" type="warning" @click="importDialog = true">导入预检</el-button>
          <el-button v-hasPermi="['catalog:price:manage']" type="success" @click="priceDialog = true">价格版本</el-button>
          <el-button v-hasPermi="['catalog:label:task:read']" type="primary" plain @click="shelfLabelDrawer = true">货架价签</el-button>
        </el-space>
      </template>

      <el-table v-loading="loading" :data="products" row-key="skuId" border>
        <el-table-column prop="skuCode" label="SKU 编码" min-width="140" />
        <el-table-column prop="spuCode" label="SPU 编码" min-width="140" />
        <el-table-column prop="name" label="商品名称" min-width="200" />
        <el-table-column prop="productType" label="类型" width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="操作" width="150">
          <template #default="scope">
            <el-button
              v-if="scope.row.status !== 'ACTIVE'"
              v-hasPermi="['catalog:product:manage']"
              link
              type="primary"
              @click="setState(scope.row, 'ACTIVE')"
              >启用</el-button
            >
            <el-button v-else v-hasPermi="['catalog:product:manage']" link type="warning" @click="setState(scope.row, 'INACTIVE')">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="pagePhase === 'EMPTY'" data-testid="catalog-empty" description="当前数据范围内暂无商品" />
    </el-card>

    <el-dialog v-model="definitionDialog" title="分类 / 品牌 / 单位" width="620px">
      <el-tabs>
        <el-tab-pane label="分类">
          <el-form :model="categoryForm" label-width="100px">
            <el-form-item label="编码"><el-input v-model="categoryForm.code" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="categoryForm.name" /></el-form-item>
          </el-form>
          <el-button
            v-hasPermi="['catalog:definition:manage']"
            data-testid="catalog-save-category"
            type="primary"
            :loading="submitting"
            @click="submitCategory"
            >保存分类</el-button
          >
        </el-tab-pane>
        <el-tab-pane label="品牌">
          <el-form :model="brandForm" label-width="100px">
            <el-form-item label="编码"><el-input v-model="brandForm.code" maxlength="32" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="brandForm.name" maxlength="100" /></el-form-item>
          </el-form>
          <el-button v-hasPermi="['catalog:definition:manage']" type="primary" :loading="submitting" @click="submitBrand">保存品牌</el-button>
        </el-tab-pane>
        <el-tab-pane label="单位">
          <el-form :model="unitForm" label-width="100px">
            <el-form-item label="编码"><el-input v-model="unitForm.code" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="unitForm.name" /></el-form-item>
            <el-form-item label="小数精度"><el-input-number v-model="unitForm.decimalScale" :min="0" :max="6" /></el-form-item>
          </el-form>
          <el-button v-hasPermi="['catalog:definition:manage']" type="primary" :loading="submitting" @click="submitUnit">保存单位</el-button>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <el-dialog v-model="productDialog" title="新增商品与销售单位" width="920px" destroy-on-close>
      <el-form :model="productForm" label-width="110px">
        <el-form-item label="SPU 编码"><el-input v-model="productForm.spuCode" /></el-form-item>
        <el-form-item label="SKU 编码"><el-input v-model="productForm.skuCode" /></el-form-item>
        <el-form-item label="商品名称"><el-input v-model="productForm.name" /></el-form-item>
        <el-form-item label="分类 ID"><el-input v-model="productForm.categoryId" /></el-form-item>
        <el-form-item label="品牌 ID"><el-input v-model="productForm.brandId" placeholder="可选" /></el-form-item>
        <el-form-item label="商品类型">
          <el-select v-model="productForm.productType" class="w-full">
            <el-option label="标准" value="STANDARD" /><el-option label="称重" value="WEIGHT" /><el-option label="计数" value="COUNT" />
          </el-select>
        </el-form-item>
        <el-form-item label="销售单位">
          <div class="w-full">
            <el-table :data="productUnits" row-key="rowKey" border>
              <el-table-column label="单位 ID" min-width="120">
                <template #default="scope"><el-input v-model="scope.row.unitId" placeholder="64 位 ID" /></template>
              </el-table-column>
              <el-table-column label="换算分子" width="130">
                <template #default="scope"><el-input-number v-model="scope.row.ratioNumerator" :min="1" :precision="0" /></template>
              </el-table-column>
              <el-table-column label="换算分母" width="130">
                <template #default="scope"><el-input-number v-model="scope.row.ratioDenominator" :min="1" :precision="0" /></template>
              </el-table-column>
              <el-table-column label="主单位" width="90">
                <template #default="scope"
                  ><el-radio v-model="primaryUnitRow" :value="scope.row.rowKey" @change="selectPrimaryUnit(scope.row.rowKey)"
                /></template>
              </el-table-column>
              <el-table-column label="条码（逗号或换行分隔）" min-width="240">
                <template #default="scope"><el-input v-model="scope.row.barcodesText" type="textarea" :rows="2" /></template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="scope"
                  ><el-button link type="danger" :disabled="productUnits.length === 1" @click="removeProductUnit(scope.$index)"
                    >删除</el-button
                  ></template
                >
              </el-table-column>
            </el-table>
            <el-button class="mt-2" icon="Plus" @click="addProductUnit">增加销售单位</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="productDialog = false">取消</el-button
        ><el-button v-hasPermi="['catalog:product:manage']" type="primary" :loading="submitting" @click="submitProduct">保存</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="importDialog" title="商品导入预检（JSON 行数组）" width="760px">
      <el-input v-model="importKey" placeholder="幂等键，例如 catalog-import-20260816-001" class="mb-2" />
      <el-input v-model="importJson" type="textarea" :rows="12" placeholder='[{"rowNumber":1,...}]' />
      <el-alert v-if="importResult" class="mt-2" :type="importResult.batch.errorCount ? 'error' : 'success'" :closable="false">
        批次 {{ importResult.batch.importBatchId }}：{{ importResult.batch.rowCount }} 行，{{ importResult.batch.errorCount }} 个错误
      </el-alert>
      <el-table v-if="importResult?.errors.length" class="mt-2" :data="importResult.errors" max-height="260" border>
        <el-table-column prop="rowNumber" label="行号" width="90" />
        <el-table-column prop="field" label="字段" min-width="150" />
        <el-table-column prop="message" label="错误明细" min-width="360" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button v-hasPermi="['catalog:import:preflight']" type="primary" :loading="submitting" @click="runPreflight">执行预检</el-button>
        <el-button
          v-if="importResult?.batch.state === 'PRECHECKED'"
          v-hasPermi="['catalog:import:publish']"
          type="success"
          :loading="submitting"
          @click="commitImport"
          >原子发布</el-button
        >
        <el-button
          v-if="importResult?.batch.state === 'PUBLISHED'"
          v-hasPermi="['catalog:import:publish']"
          type="warning"
          :loading="submitting"
          @click="rollbackPublishedImport"
          >安全回退</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="priceDialog" title="价格版本" width="640px">
      <el-form :model="priceBookForm" label-width="120px">
        <el-form-item label="价格簿编码"><el-input v-model="priceBookForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="priceBookForm.name" /></el-form-item>
        <el-form-item label="版本"><el-input-number v-model="priceBookForm.versionNo" :min="1" /></el-form-item>
        <el-form-item label="范围"
          ><el-select v-model="priceBookForm.scopeType"><el-option value="TENANT_BASE" /><el-option value="STORE" /></el-select
        ></el-form-item>
        <el-form-item v-if="priceBookForm.scopeType === 'STORE'" label="适用门店">
          <el-select v-model="priceBookStoreId" class="w-full" filterable>
            <el-option v-for="store in stores" :key="store.storeId" :label="`${store.code} - ${store.name}`" :value="String(store.storeId)" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert v-if="currentBook" :type="currentBook.state === 'PUBLISHED' ? 'success' : 'info'" :closable="false">
        价格簿 {{ currentBook.priceBookId }} · {{ currentBook.state
        }}<span v-if="currentBook.contentSha256"> · 摘要 {{ currentBook.contentSha256.slice(0, 12) }}…</span>
      </el-alert>
      <el-form v-if="currentBook" :model="priceItemForm" label-width="120px" class="mt-3">
        <el-form-item label="SKU ID"><el-input v-model="priceItemForm.skuId" /></el-form-item>
        <el-form-item label="单位 ID"><el-input v-model="priceItemForm.unitId" /></el-form-item>
        <el-form-item label="售价（分）"><el-input-number v-model="priceItemForm.amountMinor" :min="0" :precision="0" /></el-form-item>
        <el-form-item label="生效时间"
          ><el-date-picker v-model="priceItemForm.effectiveFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceDialog = false">关闭</el-button>
        <el-button v-hasPermi="['catalog:price:manage']" type="primary" :loading="submitting" @click="submitPriceBook">创建草稿</el-button>
        <el-button v-if="currentBook" v-hasPermi="['catalog:price:manage']" type="warning" :loading="submitting" @click="submitPriceItem"
          >添加价格项</el-button
        >
        <el-button v-if="currentBook" v-hasPermi="['catalog:price:publish']" type="success" :loading="submitting" @click="publishBook"
          >发布版本</el-button
        >
      </template>
    </el-dialog>
    <ShelfLabelPanel v-model="shelfLabelDrawer" :stores="stores" />
  </div>
</template>

<script setup name="CatalogWorkbench" lang="ts">
import {
  addPriceItem,
  changeProductState,
  createBrand,
  createCategory,
  createPriceBook,
  createProduct,
  createUnit,
  listProducts,
  preflightImport,
  publishImport,
  publishPriceBook,
  rollbackImport
} from '@/api/catalog';
import type { CreatePriceBookForm, CreateProductForm, ImportPreflightVO, ImportRow, PriceBookVO, ProductState, ProductVO } from '@/api/catalog/types';
import { listStores } from '@/api/foundation';
import type { StoreVO } from '@/api/foundation/types';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import { normalizeProductUnits } from '@/views/operations/model';
import type { ProductUnitDraft } from '@/views/operations/model';
import ShelfLabelPanel from './components/ShelfLabelPanel.vue';

const loading = ref(false);
const { phase: pagePhase, failure: pageFailure, submitting, runRead, runWrite } = useRecoverablePage('CATALOG_OPERATION_FAILED');
const products = ref<ProductVO[]>([]);
const definitionDialog = ref(false);
const productDialog = ref(false);
const importDialog = ref(false);
const priceDialog = ref(false);
const shelfLabelDrawer = ref(false);
const stores = ref<StoreVO[]>([]);
const categoryForm = reactive({ code: '', name: '', sortNo: 0 });
const brandForm = reactive({ code: '', name: '' });
const unitForm = reactive({ code: '', name: '', decimalScale: 0 });
const productForm = reactive<CreateProductForm>({
  spuCode: '',
  skuCode: '',
  name: '',
  categoryId: '',
  productType: 'STANDARD',
  attributes: { schemaVersion: '1.0' },
  units: []
});
let unitSequence = 0;
const productUnits = ref<ProductUnitDraft[]>([]);
const primaryUnitRow = ref('');
const importKey = ref('catalog-import-20260816-001');
const importJson = ref('[]');
const importResult = ref<ImportPreflightVO>();
const priceBookForm = reactive<CreatePriceBookForm>({ code: '', name: '', versionNo: 1, scopeType: 'TENANT_BASE' });
const priceBookStoreId = ref('');
const currentBook = ref<PriceBookVO>();
const priceItemForm = reactive({ skuId: '', unitId: '', amountMinor: 0, effectiveFrom: '2026-08-16T00:00:00Z' });

const loadProducts = async () => {
  loading.value = true;
  try {
    await runRead(
      async () => {
        const [productResponse, storeResponse] = await Promise.all([listProducts(), listStores()]);
        products.value = productResponse.data;
        stores.value = storeResponse.data;
        return products.value;
      },
      (value) => value.length === 0
    );
  } finally {
    loading.value = false;
  }
};
const submitCategory = async () => {
  const response = await runWrite(`catalog:category:${categoryForm.code}`, () => createCategory({ ...categoryForm }));
  if (!response) return;
  ElMessage.success('分类已创建');
};
const submitBrand = async () => {
  const response = await runWrite(`catalog:brand:${brandForm.code}`, () => createBrand({ ...brandForm }));
  if (!response) return;
  ElMessage.success('品牌已创建');
};
const submitUnit = async () => {
  const response = await runWrite(`catalog:unit:${unitForm.code}`, () => createUnit({ ...unitForm }));
  if (!response) return;
  ElMessage.success('单位已创建');
};
const newUnitRow = (primary = false): ProductUnitDraft => {
  unitSequence += 1;
  return {
    rowKey: `unit-${unitSequence}`,
    unitId: '',
    ratioNumerator: 1,
    ratioDenominator: 1,
    primary,
    barcodesText: ''
  };
};
const openProductDialog = () => {
  Object.assign(productForm, {
    spuCode: '',
    skuCode: '',
    name: '',
    categoryId: '',
    brandId: undefined,
    productType: 'STANDARD',
    attributes: { schemaVersion: '1.0' },
    units: []
  });
  const first = newUnitRow(true);
  productUnits.value = [first];
  primaryUnitRow.value = first.rowKey;
  productDialog.value = true;
};
const addProductUnit = () => productUnits.value.push(newUnitRow(false));
const removeProductUnit = (index: number) => {
  const removed = productUnits.value.splice(index, 1)[0];
  if (removed?.primary && productUnits.value.length > 0) selectPrimaryUnit(productUnits.value[0].rowKey);
};
const selectPrimaryUnit = (rowKey: string) => {
  primaryUnitRow.value = rowKey;
  productUnits.value.forEach((item) => (item.primary = item.rowKey === rowKey));
};
const submitProduct = async () => {
  const payload: CreateProductForm = {
    ...productForm,
    units: normalizeProductUnits(productUnits.value)
  };
  const response = await runWrite(`catalog:product:${payload.skuCode}`, () => createProduct(payload));
  if (!response) return;
  productDialog.value = false;
  ElMessage.success('商品已创建');
  await loadProducts();
};
const setState = async (product: ProductVO, state: ProductState) => {
  const response = await runWrite(`catalog:product-state:${product.skuId}:${product.version}:${state}`, () =>
    changeProductState(product.skuId, state, product.version)
  );
  if (!response) return;
  await loadProducts();
};
const runPreflight = async () => {
  const response = await runWrite(`catalog:import-preflight:${importKey.value}`, async () => {
    const rows = JSON.parse(importJson.value) as ImportRow[];
    if (!Array.isArray(rows)) throw new Error('导入内容必须是 JSON 数组');
    return preflightImport(importKey.value, rows);
  });
  if (response) importResult.value = response.data;
};
const commitImport = async () => {
  if (!importResult.value) return;
  const batchId = importResult.value.batch.importBatchId;
  const response = await runWrite(`catalog:import-publish:${batchId}`, () => publishImport(batchId));
  if (!response) return;
  importResult.value.batch = response.data;
  ElMessage.success('导入版本已原子发布');
};
const rollbackPublishedImport = async () => {
  if (!importResult.value) return;
  const batchId = importResult.value.batch.importBatchId;
  const response = await runWrite(`catalog:import-rollback:${batchId}`, () => rollbackImport(batchId));
  if (!response) return;
  importResult.value.batch = response.data;
  ElMessage.success('已按批次状态安全回退；后续修改不会被覆盖');
  await loadProducts();
};
const submitPriceBook = async () => {
  const response = await runWrite(`catalog:price-book:${priceBookForm.code}:${priceBookForm.versionNo}`, () =>
    createPriceBook({
      ...priceBookForm,
      storeId: priceBookForm.scopeType === 'STORE' ? priceBookStoreId.value : undefined
    })
  );
  if (!response) return;
  currentBook.value = response.data;
  ElMessage.success('价格草稿已创建');
};
const publishBook = async () => {
  if (!currentBook.value) return;
  const bookId = currentBook.value.priceBookId;
  const response = await runWrite(`catalog:price-publish:${bookId}`, () => publishPriceBook(bookId));
  if (!response) return;
  currentBook.value = response.data;
  ElMessage.success('价格版本已发布');
};
const submitPriceItem = async () => {
  if (!currentBook.value) return;
  const bookId = currentBook.value.priceBookId;
  const response = await runWrite(`catalog:price-item:${bookId}:${priceItemForm.skuId}:${priceItemForm.effectiveFrom}`, () =>
    addPriceItem(bookId, { ...priceItemForm })
  );
  if (!response) return;
  ElMessage.success('价格项已加入草稿');
};

onMounted(loadProducts);
</script>
