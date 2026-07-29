<template>
  <div class="favorites-page">
    <el-card class="header-card">
      <div class="page-header">
        <div class="header-left">
          <el-icon :size="20" color="#e6a23c"><Star /></el-icon>
          <span class="page-title">我的收藏</span>
          <el-tag size="small" type="warning" round>{{ favorites.length }} 个币种</el-tag>
        </div>
        <el-button :icon="Refresh" size="small" :loading="loading" @click="loadFavorites" circle />
      </div>
    </el-card>

    <!-- 空态 -->
    <el-card v-if="!loading && favorites.length === 0" class="empty-card">
      <div class="empty-content">
        <el-icon :size="48" color="#c0c4cc"><Star /></el-icon>
        <p>暂无收藏</p>
        <span class="empty-hint">在推荐列表中点击星标即可收藏</span>
        <el-button type="warning" plain @click="$router.push('/coins')">去推荐列表查看</el-button>
      </div>
    </el-card>

    <!-- 收藏列表 -->
    <el-card v-else class="table-card">
      <el-table :data="favorites" stripe style="width: 100%" v-loading="loading">
        <el-table-column type="index" label="#" width="55" />
        <el-table-column prop="baseAsset" label="币种" min-width="100">
          <template #default="{ row }">
            <span class="coin-name">{{ row.baseAsset }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="symbol" label="交易对" min-width="140" />
        <el-table-column label="方向" min-width="90">
          <template #default="{ row }">
            <el-tag
              :type="row.direction === 'LONG' ? 'success' : 'danger'"
              size="small"
              effect="dark"
              round
            >
              {{ row.direction === 'LONG' ? '做多' : '做空' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="评分" min-width="80">
          <template #default="{ row }">
            <span class="score-val" :class="scoreClass(row.score)">{{ row.score }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="price" label="价格 (USDT)" min-width="130">
          <template #default="{ row }">
            <span class="price">${{ formatPrice(row.price) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="推荐理由" min-width="200">
          <template #default="{ row }">
            <span class="reason">{{ row.reason || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="收藏时间" min-width="160" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <div class="action-btns">
              <el-button type="primary" size="small" link @click="goDetail(row.symbol)">
                <el-icon><TrendCharts /></el-icon>
                K线详情
              </el-button>
              <el-button type="danger" size="small" link @click="handleRemove(row)">
                <el-icon><Delete /></el-icon>
                取消收藏
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Star, Refresh, TrendCharts, Delete } from '@element-plus/icons-vue'
import { getFavorites, removeFavorite } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const favorites = ref([])
const loading = ref(false)

function formatPrice(p) {
  const n = parseFloat(p)
  if (isNaN(n)) return p
  if (n >= 1000) return n.toFixed(0)
  if (n >= 1) return n.toFixed(2)
  if (n >= 0.01) return n.toFixed(4)
  if (n >= 0.0001) return n.toFixed(6)
  return n.toFixed(8)
}

function scoreClass(s) {
  if (s >= 80) return 'score-high'
  if (s >= 65) return 'score-mid'
  return 'score-low'
}

function goDetail(symbol) {
  router.push(`/coins/${symbol}`)
}

async function loadFavorites() {
  loading.value = true
  try {
    const res = await getFavorites()
    if (res.data.code === 0) {
      favorites.value = res.data.data || []
    }
  } catch (e) {
    // 静默处理
  } finally {
    loading.value = false
  }
}

async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(`确定取消收藏 ${row.baseAsset}？`, '提示', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await removeFavorite(row.symbol)
  ElMessage.success('已取消收藏')
  favorites.value = favorites.value.filter(f => f.symbol !== row.symbol)
}

onMounted(loadFavorites)
</script>

<style scoped>
.favorites-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  padding: 0 4px;
}

.header-card {
  flex-shrink: 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.empty-card {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 40px;
}

.empty-content p {
  font-size: 16px;
  color: #606266;
  margin: 0;
}

.empty-hint {
  font-size: 13px;
  color: #909399;
}

.table-card {
  flex: 1;
  overflow: hidden;
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

.score-val {
  font-weight: 700;
  font-size: 16px;
  font-family: 'Courier New', monospace;
}

.score-high { color: #67c23a; }
.score-mid { color: #e6a23c; }
.score-low { color: #909399; }

.reason {
  font-size: 13px;
  color: #606266;
}

.action-btns {
  display: flex;
  align-items: center;
  gap: 2px;
  white-space: nowrap;
}
</style>
