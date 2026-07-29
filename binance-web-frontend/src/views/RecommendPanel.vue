<template>
  <el-card class="recommend-panel" shadow="never">
    <template #header>
      <div class="panel-header">
        <div class="panel-title">
          <el-icon :size="18" color="#409EFF"><TrendCharts /></el-icon>
          <span>推荐购买</span>
        </div>
        <div class="panel-actions">
          <el-tooltip content="手动刷新扫描" placement="top">
            <el-button :icon="Refresh" circle size="small" :loading="scanning" @click="handleScan" />
          </el-tooltip>
        </div>
      </div>
    </template>

    <!-- 加载中 -->
    <div v-if="loading" class="panel-loading">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <p>正在扫描市场...</p>
    </div>

    <!-- 无推荐 -->
    <div v-else-if="list.length === 0" class="panel-empty">
      <el-icon :size="36" color="#c0c4cc"><WarningFilled /></el-icon>
      <p>暂无推荐</p>
      <span class="empty-hint">等待扫描或未满足开仓条件</span>
      <el-button size="small" type="primary" plain @click="handleScan" :loading="scanning">
        立即扫描
      </el-button>
    </div>

    <!-- 推荐列表 -->
    <div v-else class="recommend-list">
      <div
        v-for="item in list"
        :key="item.symbol"
        class="recommend-item"
        :class="item.direction === 'LONG' ? 'item-long' : 'item-short'"
      >
        <!-- 第一行: 币种 + 方向标签 + 收藏 + 评分 -->
        <div class="item-row1">
          <el-button
            :icon="Star"
            circle
            size="small"
            :type="favoritedSymbols.has(item.symbol) ? 'warning' : 'default'"
            :class="{ 'star-fav': favoritedSymbols.has(item.symbol) }"
            @click.stop="toggleFavorite(item)"
            title="收藏/取消收藏"
          />
          <span class="item-symbol" :title="item.symbol">{{ item.baseAsset }}</span>
          <el-tag
            :type="item.direction === 'LONG' ? 'success' : 'danger'"
            size="small"
            effect="dark"
            round
          >
            {{ item.direction === 'LONG' ? '📈 做多' : '📉 做空' }}
          </el-tag>
          <span class="item-score" :class="scoreClass(item.score)">
            {{ item.score }}
          </span>
        </div>

        <!-- 推荐购买时间 -->
        <div v-if="item.recommendTime" class="item-recommend-time">
          <span class="rt-icon">⏰</span>
          <span class="rt-label">推荐购买时间：</span>
          <span class="rt-value">{{ item.recommendTime }}</span>
        </div>

        <!-- 第二行: 价格 + 24h涨跌 -->
        <div class="item-row2">
          <span class="item-price">${{ formatPrice(item.price) }}</span>
          <span
            class="item-change"
            :class="parseFloat(item.change24h) >= 0 ? 'change-up' : 'change-down'"
          >
            {{ formatChange(item.change24h) }}
          </span>
        </div>

        <!-- 第三行: 成交量 -->
        <div class="item-row3">
          <span class="item-vol">24h量 {{ item.volume }}</span>
          <span class="item-signal" :class="signalClass(item.signal)">{{ item.signal }}</span>
        </div>

        <!-- 第四行: 理由 -->
        <div class="item-reason" v-if="item.reason">
          <el-icon :size="14"><InfoFilled /></el-icon>
          <span>{{ item.reason }}</span>
        </div>

        <!-- 进度条 -->
        <div class="item-bar">
          <div class="bar-track">
            <div
              class="bar-fill"
              :class="item.direction === 'LONG' ? 'bar-long' : 'bar-short'"
              :style="{ width: item.score + '%' }"
            />
          </div>
        </div>

        <!-- 更新时间 -->
        <div class="item-time">{{ item.updateTime }}</div>
      </div>
    </div>

    <!-- 底部说明 -->
    <div class="panel-footer">
      <el-text size="small" type="info">每10分钟自动扫描 | 仅供参考</el-text>
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { TrendCharts, Refresh, Loading, WarningFilled, InfoFilled, Star } from '@element-plus/icons-vue'
import { getRecommendations, triggerScan, addFavorite, removeFavorite, getFavorites } from '../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const loading = ref(true)
const scanning = ref(false)
const favoritedSymbols = ref(new Set())
let timer = null

function formatPrice(p) {
  const n = parseFloat(p)
  if (isNaN(n)) return p
  if (n >= 1000) return n.toFixed(0)
  if (n >= 1) return n.toFixed(2)
  if (n >= 0.01) return n.toFixed(4)
  if (n >= 0.0001) return n.toFixed(6)
  return n.toFixed(8)
}

function formatChange(c) {
  const n = parseFloat(c)
  if (isNaN(n)) return '0.00%'
  const sign = n >= 0 ? '+' : ''
  return sign + n.toFixed(2) + '%'
}

function scoreClass(s) {
  if (s >= 80) return 'score-high'
  if (s >= 65) return 'score-mid'
  return 'score-low'
}

function signalClass(s) {
  if (s === '强信号') return 'signal-high'
  if (s === '中等信号') return 'signal-mid'
  return 'signal-low'
}

async function loadRecommendations() {
  try {
    const res = await getRecommendations()
    if (res.data.code === 0) {
      list.value = res.data.data || []
    }
  } catch (e) {
    // 静默处理
  } finally {
    loading.value = false
  }
}

async function handleScan() {
  scanning.value = true
  try {
    const res = await triggerScan()
    if (res.data.code === 0) {
      list.value = res.data.data || []
      ElMessage.success(`扫描完成，共 ${list.value.length} 个推荐`)
    }
  } catch (e) {
    ElMessage.error('扫描失败')
  } finally {
    scanning.value = false
  }
}

async function loadFavorited() {
  try {
    const res = await getFavorites()
    if (res.data.code === 0) {
      const favs = res.data.data || []
      favoritedSymbols.value = new Set(favs.map(f => f.symbol))
    }
  } catch (e) { /* ignore */ }
}

async function toggleFavorite(item) {
  const sym = item.symbol
  try {
    if (favoritedSymbols.value.has(sym)) {
      await removeFavorite(sym)
      favoritedSymbols.value.delete(sym)
      // 触发响应式更新
      favoritedSymbols.value = new Set(favoritedSymbols.value)
      ElMessage.success(`已取消收藏 ${item.baseAsset}`)
    } else {
      await addFavorite(sym, {
        baseAsset: item.baseAsset,
        direction: item.direction,
        score: item.score,
        price: item.price,
        reason: item.reason,
        volume: item.volume,
        change24h: item.change24h,
        recommendTime: item.recommendTime
      })
      favoritedSymbols.value.add(sym)
      favoritedSymbols.value = new Set(favoritedSymbols.value)
      ElMessage.success(`已收藏 ${item.baseAsset}`)
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

onMounted(() => {
  loadFavorited()
  loadRecommendations()
  // 每30秒自动拉取最新推荐（后端10分钟扫一次，这里只拉缓存）
  timer = setInterval(loadRecommendations, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.recommend-panel {
  width: 320px;
  min-width: 320px;
  height: 100%;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #ebeef5;
}

.recommend-panel :deep(.el-card__body) {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.panel-loading,
.panel-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 16px;
  color: #909399;
  gap: 8px;
}

.empty-hint {
  font-size: 12px;
  color: #c0c4cc;
  margin-bottom: 8px;
}

/* 推荐列表 */
.recommend-list {
  padding: 0;
}

.recommend-item {
  padding: 12px 16px;
  border-bottom: 1px solid #f2f3f5;
  transition: background 0.2s;
}

.recommend-item:hover {
  background: #fafafa;
}

.recommend-item.item-long {
  border-left: 3px solid #67c23a;
}

.recommend-item.item-short {
  border-left: 3px solid #f56c6c;
}

/* 第一行 */
.item-row1 {
  display: flex;
  align-items: center;
  gap: 8px;
}

.item-symbol {
  font-weight: 700;
  font-size: 15px;
  color: #303133;
  min-width: 50px;
}

.item-score {
  margin-left: auto;
  font-weight: 700;
  font-size: 18px;
  font-family: 'Courier New', monospace;
}

.score-high { color: #67c23a; }
.score-mid { color: #e6a23c; }
.score-low { color: #909399; }

/* 收藏星标 */
.item-row1 .el-button {
  flex-shrink: 0;
  padding: 4px;
}

.star-fav.el-button--warning {
  --el-button-bg-color: #fef0c7;
  --el-button-border-color: #fec84b;
  --el-button-text-color: #dc6803;
}

/* 推荐购买时间 */
.item-recommend-time {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 4px 10px;
  background: linear-gradient(135deg, #ecf5ff, #d9ecff);
  border-radius: 6px;
  border: 1px dashed #a0cfff;
}

.rt-icon {
  font-size: 13px;
}

.rt-label {
  font-size: 12px;
  color: #606266;
}

.rt-value {
  font-size: 13px;
  font-weight: 700;
  color: #409EFF;
}

/* 第二行 */
.item-row2 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}

.item-price {
  font-size: 14px;
  color: #606266;
  font-family: 'Courier New', monospace;
}

.item-change {
  font-size: 13px;
  font-weight: 600;
}

.change-up { color: #67c23a; }
.change-down { color: #f56c6c; }

/* 第三行 */
.item-row3 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.item-vol {
  font-size: 12px;
  color: #909399;
}

.item-signal {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
}

.signal-high {
  background: #e1f3d8;
  color: #529b2e;
}

.signal-mid {
  background: #faecd8;
  color: #b88230;
}

.signal-low {
  background: #f0f2f5;
  color: #909399;
}

/* 理由 */
.item-reason {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}

.item-reason span {
  flex: 1;
}

/* 进度条 */
.item-bar {
  margin-top: 8px;
}

.bar-track {
  height: 4px;
  background: #f0f2f5;
  border-radius: 2px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.5s ease;
}

.bar-long {
  background: linear-gradient(90deg, #67c23a, #85ce61);
}

.bar-short {
  background: linear-gradient(90deg, #f56c6c, #f89898);
}

/* 更新时间 */
.item-time {
  text-align: right;
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 4px;
}

/* 底部 */
.panel-footer {
  padding: 8px 16px;
  text-align: center;
  border-top: 1px solid #f2f3f5;
  background: #fafafa;
}
</style>
