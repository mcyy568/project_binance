<template>
  <div class="coin-page">
    <!-- 左侧推荐面板 -->
    <RecommendPanel />

    <!-- 右侧内容 -->
    <div class="coin-list">
      <!-- 搜索和筛选 -->
      <el-card class="search-card">
        <div class="search-left">
          <el-input
            v-model="searchText"
            placeholder="搜索币种（如 BTC）"
            clearable
            :prefix-icon="Search"
            style="width: 280px"
            @input="filterCoins"
          />
          <span class="total-info">共 {{ filteredCoins.length }} 个交易对</span>
        </div>
      </el-card>

      <!-- 表格 -->
      <el-card class="table-card">
        <el-table
          :data="paginatedCoins"
          v-loading="loading"
          stripe
          style="width: 100%"
          height="calc(100vh - 230px)"
        >
          <el-table-column type="index" label="#" width="55" />
          <el-table-column prop="baseAsset" label="币种" min-width="100">
            <template #default="{ row }">
              <span class="coin-name">{{ row.baseAsset }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="symbol" label="交易对" min-width="140" />
          <el-table-column prop="price" label="最新价 (USDT)" min-width="160">
            <template #default="{ row }">
              <span class="price">{{ formatPrice(row.price) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="quoteAssetPrecision" label="精度" min-width="80" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button
                type="primary"
                size="small"
                link
                @click="goDetail(row.symbol)"
              >
                <el-icon><TrendCharts /></el-icon>
                K线详情
              </el-button>
              <el-button
                type="warning"
                size="small"
                link
                :loading="scoringSymbol === row.symbol"
                @click="handleScore(row)"
              >
                <el-icon :size="14"><DataAnalysis /></el-icon>
                评分
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div class="pagination">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[20, 50, 100]"
            :total="filteredCoins.length"
            layout="total, sizes, prev, pager, next"
            background
            @size-change="handleSizeChange"
          />
        </div>
      </el-card>
    </div>

    <!-- 评分结果弹窗 -->
    <el-dialog v-model="scoreVisible" :title="`${scoreResult.symbol} 评分详情`" width="500px" destroy-on-close>
      <div v-if="scoreLoading" style="text-align:center;padding:40px">
        <el-icon class="is-loading" :size="32"><Loading /></el-icon>
        <p>正在计算评分...</p>
      </div>
      <div v-else class="score-detail">
        <!-- 总体结果 -->
        <div class="score-overview">
          <div class="score-circle" :class="scoreLevelClass">
            <span class="score-num">{{ scoreResult.score }}</span>
            <span class="score-unit">分</span>
          </div>
          <div class="score-meta">
            <span class="score-dir" :class="scoreResult.direction === 'LONG' ? 'dir-long' : 'dir-short'">
              {{ scoreResult.direction === 'LONG' ? '📈 做多' : scoreResult.direction === 'SHORT' ? '📉 做空' : '—' }}
            </span>
            <el-tag :type="scoreResult.signal === '强信号' ? 'success' : scoreResult.signal === '中等信号' ? 'warning' : scoreResult.signal === '弱信号' ? 'info' : 'danger'" size="small">
              {{ scoreResult.signal }}
            </el-tag>
          </div>
        </div>

        <el-divider />

        <!-- 轻扫结果 -->
        <div class="step-row">
          <span class="step-label">LIGHT 轻扫</span>
          <el-tag :type="scoreResult.lightPass ? 'success' : 'danger'" size="small" effect="plain">
            {{ scoreResult.lightPass ? '通过' : '未通过' }}
          </el-tag>
        </div>

        <!-- 中扫结果 -->
        <div class="step-row">
          <span class="step-label">MEDIUM 中扫</span>
          <el-tag :type="scoreResult.mediumPass ? 'success' : 'danger'" size="small" effect="plain">
            {{ scoreResult.mediumPass ? '通过 (' + scoreResult.mediumScore + '分)' : '未通过 (' + scoreResult.mediumScore + '分)' }}
          </el-tag>
        </div>
        <div v-if="scoreResult.mediumPass" class="step-detail">
          <span>动量 {{ scoreResult.momentum }}</span>
          <span>量比 {{ scoreResult.volumeRatio }}</span>
          <span>压缩 {{ scoreResult.compression }}</span>
          <span>突破 {{ scoreResult.breakout ? '√' : '×' }}</span>
        </div>

        <el-divider />

        <!-- 推荐理由 -->
        <div class="reason-row">
          <span class="reason-label">理由：</span>
          <span class="reason-text">{{ scoreResult.reason || '—' }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="scoreVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, TrendCharts, DataAnalysis, Loading } from '@element-plus/icons-vue'
import { getCoins, scoreCoin } from '../api'
import { ElMessage } from 'element-plus'
import RecommendPanel from './RecommendPanel.vue'

const router = useRouter()
const loading = ref(false)
const coins = ref([])
const searchText = ref('')
const currentPage = ref(1)
const pageSize = ref(50)

// 评分相关
const scoringSymbol = ref('')
const scoreVisible = ref(false)
const scoreLoading = ref(true)
const scoreResult = ref({
  symbol: '', baseAsset: '', price: '', volume: '', change24h: '',
  lightPass: false, mediumPass: false, mediumScore: 0,
  momentum: '', volumeRatio: '', compression: '', breakout: false,
  score: 0, direction: '-', signal: '', reason: ''
})

const scoreLevelClass = computed(() => {
  const s = scoreResult.value.score
  if (s >= 80) return 'score-high'
  if (s >= 65) return 'score-mid'
  if (s >= 55) return 'score-low'
  return 'score-none'
})

const filteredCoins = computed(() => {
  if (!searchText.value) return coins.value
  const keyword = searchText.value.toUpperCase()
  return coins.value.filter(c =>
    c.baseAsset.includes(keyword) || c.symbol.includes(keyword)
  )
})

const paginatedCoins = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredCoins.value.slice(start, start + pageSize.value)
})

function filterCoins() {
  currentPage.value = 1
}

function handleSizeChange() {
  currentPage.value = 1
}

function formatPrice(price) {
  const num = parseFloat(price)
  if (isNaN(num)) return price
  if (num >= 1) return num.toFixed(2)
  if (num >= 0.01) return num.toFixed(4)
  return num.toFixed(8)
}

function goDetail(symbol) {
  router.push({ name: 'CoinDetail', params: { symbol } })
}

async function handleScore(row) {
  scoringSymbol.value = row.symbol
  scoreVisible.value = true
  scoreLoading.value = true
  scoreResult.value = { symbol: row.symbol, baseAsset: row.baseAsset, score: 0, direction: '-', signal: '', reason: '' }
  try {
    const res = await scoreCoin(row.symbol)
    if (res.data.code === 0) {
      scoreResult.value = res.data.data
    } else {
      ElMessage.error('评分失败')
      scoreVisible.value = false
    }
  } catch (e) {
    ElMessage.error('请求失败: ' + e.message)
    scoreVisible.value = false
  } finally {
    scoringSymbol.value = ''
    scoreLoading.value = false
  }
}

async function loadCoins() {
  loading.value = true
  try {
    const res = await getCoins()
    if (res.data.code === 0) {
      coins.value = res.data.data
    } else {
      ElMessage.error('获取币种列表失败')
    }
  } catch (e) {
    ElMessage.error('请求失败：' + e.message)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadCoins()
})
</script>

<style scoped>
.coin-page {
  display: flex;
  height: 100%;
  gap: 0;
}

.coin-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  padding: 0 4px;
  flex: 1;
  min-width: 0;
}

.search-card {
  flex-shrink: 0;
}

.search-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.total-info {
  color: #909399;
  font-size: 14px;
  white-space: nowrap;
}

.coin-name {
  font-weight: 600;
  color: #303133;
}

.price {
  font-family: 'Courier New', monospace;
  font-weight: 600;
  color: #409EFF;
}

.table-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.table-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .coin-page {
    flex-direction: column;
  }
  .coin-page :deep(.recommend-panel) {
    width: 100% !important;
    min-width: 100% !important;
    max-height: 40vh;
  }
}

.score-detail {
  padding: 0 4px;
}

.score-overview {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 8px;
}

.score-circle {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 3px solid;
}

.score-circle.score-high { border-color: #67c23a; background: #f0f9eb; }
.score-circle.score-mid { border-color: #e6a23c; background: #fdf6ec; }
.score-circle.score-low { border-color: #909399; background: #f4f4f5; }
.score-circle.score-none { border-color: #f56c6c; background: #fef0f0; }

.score-num {
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
}

.score-high .score-num { color: #67c23a; }
.score-mid .score-num { color: #e6a23c; }
.score-low .score-num { color: #909399; }
.score-none .score-num { color: #f56c6c; }

.score-unit {
  font-size: 11px;
  color: #909399;
}

.score-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.score-dir {
  font-size: 16px;
  font-weight: 600;
}

.dir-long { color: #67c23a; }
.dir-short { color: #f56c6c; }

.step-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}

.step-label {
  font-weight: 600;
  color: #303133;
}

.step-detail {
  display: flex;
  gap: 16px;
  padding: 4px 0 8px;
  font-size: 13px;
  color: #606266;
}

.reason-row {
  display: flex;
  gap: 8px;
}

.reason-label {
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
}

.reason-text {
  color: #606266;
}

@media (max-width: 900px) {
  .coin-page {
    flex-direction: column;
  }
  .coin-page :deep(.recommend-panel) {
    width: 100% !important;
    min-width: 100% !important;
    max-height: 40vh;
  }
}

@media (max-width: 768px) {
  .search-left {
    flex-direction: column;
    align-items: flex-start;
  }
  .search-left .el-input {
    width: 100% !important;
  }
}
</style>
