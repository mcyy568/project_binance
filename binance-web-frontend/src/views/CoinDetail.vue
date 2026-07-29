<template>
  <div class="coin-detail">
    <!-- 面包屑导航 -->
    <div class="breadcrumb">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/coins' }">
          <el-icon><List /></el-icon> 币种列表
        </el-breadcrumb-item>
        <el-breadcrumb-item>{{ symbol }} 15分钟K线图</el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <!-- 概览卡片 -->
    <el-row :gutter="16" class="overview-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">交易对</div>
            <div class="stat-value">{{ symbol }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">最新价</div>
            <div class="stat-value price-color">{{ latestPrice }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">最高价</div>
            <div class="stat-value up-color">{{ latestHigh }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-label">最低价</div>
            <div class="stat-value down-color">{{ latestLow }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快速切换币种 -->
    <el-card class="switch-card">
      <div class="switch-row">
        <el-input
          v-model="switchInput"
          placeholder="输入其他币名切换K线，如 ETH"
          clearable
          :prefix-icon="Search"
          class="switch-input"
          @keyup.enter="switchSymbol"
        >
          <template #append>
            <el-button :icon="TrendCharts" @click="switchSymbol">切换</el-button>
          </template>
        </el-input>
      </div>
    </el-card>

    <!-- K线图 -->
    <el-card class="chart-card">
      <template #header>
        <div class="chart-header">
          <span>{{ symbol }} - 15分钟K线图</span>
          <el-button
            type="primary"
            size="small"
            :loading="refreshing"
            @click="refreshData"
          >
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </template>
      <div v-loading="loading" class="chart-container">
        <div ref="chartRef" class="chart"></div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { List, Refresh, Search, TrendCharts } from '@element-plus/icons-vue'
import { getKlines } from '../api'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'

const route = useRoute()
const router = useRouter()
const symbol = computed(() => route.params.symbol || '')

const loading = ref(false)
const refreshing = ref(false)
const klineData = ref([])
const chartRef = ref(null)
const switchInput = ref('')
let chartInstance = null

const latestPrice = computed(() => {
  if (klineData.value.length === 0) return '--'
  const last = klineData.value[klineData.value.length - 1]
  return formatPrice(last.close)
})

const latestHigh = computed(() => {
  if (klineData.value.length === 0) return '--'
  const last = klineData.value[klineData.value.length - 1]
  return formatPrice(last.high)
})

const latestLow = computed(() => {
  if (klineData.value.length === 0) return '--'
  const last = klineData.value[klineData.value.length - 1]
  return formatPrice(last.low)
})

function formatPrice(price) {
  const num = parseFloat(price)
  if (isNaN(num)) return price
  if (num >= 1) return num.toFixed(2)
  if (num >= 0.01) return num.toFixed(4)
  return num.toFixed(8)
}

function initChart() {
  if (!chartRef.value) return
  chartInstance = echarts.init(chartRef.value)
  updateChart()
}

function updateChart() {
  if (!chartInstance || klineData.value.length === 0) return

  const categoryData = klineData.value.map(k => {
    const d = new Date(k.openTime)
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    const hours = String(d.getHours()).padStart(2, '0')
    const minutes = String(d.getMinutes()).padStart(2, '0')
    return `${month}-${day} ${hours}:${minutes}`
  })

  const values = klineData.value.map(k => [
    parseFloat(k.open),
    parseFloat(k.close),
    parseFloat(k.low),
    parseFloat(k.high)
  ])

  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: categoryData,
      axisLabel: {
        rotate: 45,
        fontSize: 10
      },
      boundaryGap: false
    },
    yAxis: {
      type: 'value',
      scale: true,
      splitArea: {
        show: true,
        areaStyle: {
          color: ['rgba(250,250,250,0.3)', 'rgba(200,200,200,0.1)']
        }
      }
    },
    dataZoom: [
      {
        type: 'inside',
        start: 70,
        end: 100
      },
      {
        type: 'slider',
        start: 70,
        end: 100
      }
    ],
    series: [
      {
        name: symbol.value,
        type: 'candlestick',
        data: values,
        itemStyle: {
          color: '#26a69a',
          color0: '#ef5350',
          borderColor: '#26a69a',
          borderColor0: '#ef5350'
        }
      }
    ]
  })
}

function handleResize() {
  chartInstance?.resize()
}

async function loadData() {
  loading.value = true
  try {
    const res = await getKlines(symbol.value)
    if (res.data.code === 0) {
      klineData.value = res.data.data
      if (chartInstance) {
        updateChart()
      } else {
        initChart()
      }
    } else {
      ElMessage.error('获取K线数据失败')
    }
  } catch (e) {
    ElMessage.error('请求失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function refreshData() {
  refreshing.value = true
  try {
    const res = await getKlines(symbol.value)
    if (res.data.code === 0) {
      klineData.value = res.data.data
      updateChart()
      ElMessage.success('刷新成功')
    }
  } catch (e) {
    ElMessage.error('刷新失败：' + e.message)
  } finally {
    refreshing.value = false
  }
}

function switchSymbol() {
  const input = switchInput.value.trim().toUpperCase()
  if (!input) return
  // 自动补全 USDT 后缀
  const newSymbol = input.endsWith('USDT') ? input : input + 'USDT'
  switchInput.value = ''
  router.push({ name: 'CoinDetail', params: { symbol: newSymbol } })
}

watch(symbol, () => {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
  loadData()
})

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chartInstance?.dispose()
})
</script>

<style scoped>
.coin-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.breadcrumb {
  padding: 4px 0;
}

.overview-row {
  margin: 0 !important;
}

.stat-item {
  text-align: center;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  font-family: 'Courier New', monospace;
  color: #303133;
}

.price-color {
  color: #409EFF;
}

.up-color {
  color: #26a69a;
}

.down-color {
  color: #ef5350;
}

.chart-card {
  flex: 1;
}

.switch-card {
  flex-shrink: 0;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.switch-input {
  max-width: 420px;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.chart-container {
  width: 100%;
}

.chart {
  width: 100%;
  height: 500px;
}
</style>
