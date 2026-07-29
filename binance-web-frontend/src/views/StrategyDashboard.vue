<template>
  <div class="strategy-page">
    <!-- 顶部概览卡片 -->
    <div class="overview-cards">
      <el-card class="stat-card">
        <div class="stat-val">{{ overview.totalSignals || 0 }}</div>
        <div class="stat-label">信号</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val accent">{{ overview.openCount || 0 }}</div>
        <div class="stat-label">开仓</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val">{{ (overview.accountCapital || 1000).toFixed(2) }}U</div>
        <div class="stat-label">账户总额</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val" :class="overview.netProfit >= 0 ? 'up' : 'down'">
          {{ (overview.netProfit || 0) >= 0 ? '+' : '' }}{{ (overview.netProfit || 0).toFixed(2) }}U
        </div>
        <div class="stat-label">净收益</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val">{{ overview.winRate || 0 }}%</div>
        <div class="stat-label">胜率</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val">{{ overview.stopLossRate || 0 }}%</div>
        <div class="stat-label">硬止损率</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val">{{ overview.closedOpen || '0/0' }}</div>
        <div class="stat-label">已平/持</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val">{{ (overview.avgPnl || 0).toFixed(2) }}U</div>
        <div class="stat-label">平均单笔</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val">{{ profitFactor }}</div>
        <div class="stat-label">利润因子</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val down">{{ (overview.maxDrawdown || 0).toFixed(2) }}U</div>
        <div class="stat-label">最大回撤</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val">{{ (overview.maxWin || 0).toFixed(2) }}U</div>
        <div class="stat-label">最佳单笔</div>
      </el-card>
      <el-card class="stat-card">
        <div class="stat-val down">-{{ Math.abs(overview.maxLoss || 0).toFixed(2) }}U</div>
        <div class="stat-label">最差单笔</div>
      </el-card>
    </div>

    <div class="main-grid">
      <!-- 策略参数 -->
      <el-card class="section-card params-card">
        <template #header>
          <div class="card-header">
            <span>策略参数</span>
            <el-button size="small" type="primary" @click="refreshAll" :loading="loading">刷新</el-button>
          </div>
        </template>
        <div class="params-wrapper">
          <div v-for="cfg in configs" :key="cfg.id" class="param-group" :class="cfg.mode">
            <div class="param-title">{{ cfg.name }}</div>
            <div class="param-row"><span>应用条件</span><span>{{ cfg.mode === 'EARLY' ? '24H幅 <' : '24H幅 >=' }}{{ cfg.volatilityThreshold }}%</span></div>
            <div class="param-row"><span>原定止损</span><span class="down">{{ cfg.stopLossPct }}%</span></div>
            <div class="param-row"><span>利润保护启动</span><span class="up">{{ cfg.profitProtectPct }}%</span></div>
            <div class="param-row"><span>回撤退出保护</span><span>{{ cfg.drawdownExitPct }}%</span></div>
            <div class="param-row"><span>保证金</span><span>{{ cfg.positionSize }}U</span></div>
            <div class="param-row"><span>杠杆</span><span>{{ cfg.leverage }}x</span></div>
            <div class="param-row"><span>状态</span>
              <el-switch :value="cfg.enabled" @change="toggleConfig(cfg)" size="small" />
            </div>
            <el-button size="small" type="primary" link @click="editConfig(cfg)" class="edit-btn">编辑参数</el-button>
          </div>
        </div>
      </el-card>

      <!-- 每日统计 -->
      <el-card class="section-card">
        <template #header><span>每日统计</span></template>
        <el-table :data="dailyStats" stripe size="small" max-height="350" class="compact-table">
          <el-table-column prop="date" label="日期" width="110" />
          <el-table-column prop="count" label="笔数" width="60" align="center" />
          <el-table-column label="盈亏" width="110" align="right">
            <template #default="{ row }">
              <span :class="(row.pnl || 0) >= 0 ? 'up' : 'down'">{{ (row.pnl || 0).toFixed(2) }}U</span>
            </template>
          </el-table-column>
          <el-table-column label="胜率" width="70" align="center">
            <template #default="{ row }">{{ row.winRate }}%</template>
          </el-table-column>
          <el-table-column label="止损率" width="70" align="center">
            <template #default="{ row }">{{ row.stopLossRate }}%</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 当前持仓 -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          <span>当前持仓 ({{ positions.length }})</span>
          <div class="header-actions">
            <el-button size="small" @click="scanNow" :loading="scanning">手动扫描</el-button>
          </div>
        </div>
      </template>
      <el-table :data="positions" stripe size="small" max-height="300" class="compact-table">
        <el-table-column prop="baseAsset" label="币种" width="80" />
        <el-table-column prop="symbol" label="交易对" width="110" />
        <el-table-column prop="strategyName" label="策略" width="90" />
        <el-table-column label="方向" width="65" align="center">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'LONG' ? 'success' : 'danger'" size="small">{{ row.direction === 'LONG' ? '做多' : '做空' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="openPrice" label="开仓价" width="110" />
        <el-table-column prop="currentPrice" label="现值估价" width="110" />
        <el-table-column label="浮动盈亏" width="110" align="right">
          <template #default="{ row }">
            <span :class="(row.unrealizedPnl || 0) >= 0 ? 'up' : 'down'">
              {{ (row.unrealizedPnl || 0) >= 0 ? '+' : '' }}{{ (row.unrealizedPnl || 0).toFixed(2) }}U
            </span>
          </template>
        </el-table-column>
        <el-table-column label="盈亏率" width="80" align="right">
          <template #default="{ row }">
            <span :class="(row.pnlPct || 0) >= 0 ? 'up' : 'down'">
              {{ (row.pnlPct || 0).toFixed(2) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最高浮盈" width="100" align="right">
          <template #default="{ row }" class="up">{{ (row.highestPnl || 0).toFixed(2) }}U</template>
        </el-table-column>
        <el-table-column prop="openTime" label="开仓时间" width="120" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button type="danger" size="small" link @click="manualClose(row)">平仓</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="positions.length === 0 && !loading" description="暂无持仓" :image-size="60" />
    </el-card>

    <!-- 交易记录 -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          <span>交易记录</span>
          <el-radio-group v-model="tradeFilter" size="small" @change="loadTrades">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="win">盈利</el-radio-button>
            <el-radio-button value="loss">亏损</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="trades" stripe size="small" max-height="400" class="compact-table">
        <el-table-column type="index" label="#" width="40" />
        <el-table-column prop="baseAsset" label="币种" width="70" />
        <el-table-column prop="strategyName" label="策略" width="90" />
        <el-table-column label="方向" width="65" align="center">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'LONG' ? 'success' : 'danger'" size="small">{{ row.direction === 'LONG' ? '做多' : '做空' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="盈亏" width="110" align="right">
          <template #default="{ row }">
            <span :class="(row.pnl || 0) >= 0 ? 'up' : 'down'">
              {{ (row.pnl || 0) >= 0 ? '+' : '' }}{{ (row.pnl || 0).toFixed(2) }}U
            </span>
          </template>
        </el-table-column>
        <el-table-column label="盈利率" width="80" align="right">
          <template #default="{ row }">
            <span :class="(row.pnlPct || 0) >= 0 ? 'up' : 'down'">{{ (row.pnlPct || 0).toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column prop="openPrice" label="开仓" width="100" />
        <el-table-column prop="closePrice" label="平仓" width="100" />
        <el-table-column prop="holdDuration" label="时长(分)" width="80" align="center" />
        <el-table-column prop="closeReason" label="平仓原因" min-width="150" show-overflow-tooltip />
        <el-table-column prop="openTime" label="开仓时间" width="120" />
        <el-table-column prop="closeTime" label="平仓时间" width="120" />
      </el-table>
    </el-card>

    <!-- 信号记录 -->
    <el-card class="section-card" v-if="signals.length > 0">
      <template #header><span>近期信号</span></template>
      <el-table :data="signals" stripe size="small" max-height="300" class="compact-table">
        <el-table-column prop="baseAsset" label="币种" width="70" />
        <el-table-column prop="direction" label="方向" width="60" align="center">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'LONG' ? 'success' : 'danger'" size="small">{{ row.direction === 'LONG' ? '多' : '空' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="price" label="价格" width="100" />
        <el-table-column prop="mode" label="模式" width="70">
          <template #default="{ row }">{{ row.mode === 'EARLY' ? '早段' : '高位' }}</template>
        </el-table-column>
        <el-table-column prop="score" label="评分" width="60" align="center" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACCEPTED' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACCEPTED' ? '已接受' : '已跳过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="skipReason" label="原因" min-width="150" show-overflow-tooltip />
        <el-table-column prop="detectTime" label="时间" width="120" />
      </el-table>
    </el-card>

    <!-- 策略配置编辑弹窗 -->
    <el-dialog v-model="configVisible" :title="'编辑策略: ' + editTarget.name" width="480px" destroy-on-close>
      <el-form :model="editTarget" label-width="130px" label-position="left" size="small">
        <el-form-item label="策略名称">
          <el-input v-model="editTarget.name" />
        </el-form-item>
        <el-form-item label="24H波动率阈值(%)">
          <el-input-number v-model="editTarget.volatilityThreshold" :min="1" :max="100" :step="1" />
        </el-form-item>
        <el-form-item label="止损比例(%)">
          <el-input-number v-model="editTarget.stopLossPct" :min="1" :max="200" :step="5" />
        </el-form-item>
        <el-form-item label="利润保护启动(%)">
          <el-input-number v-model="editTarget.profitProtectPct" :min="1" :max="500" :step="5" />
        </el-form-item>
        <el-form-item label="回撤退出(%)">
          <el-input-number v-model="editTarget.drawdownExitPct" :min="1" :max="100" :step="1" />
        </el-form-item>
        <el-form-item label="保证金(U)">
          <el-input-number v-model="editTarget.positionSize" :min="10" :max="10000" :step="10" />
        </el-form-item>
        <el-form-item label="杠杆倍数">
          <el-input-number v-model="editTarget.leverage" :min="1" :max="125" :step="1" />
        </el-form-item>
        <el-form-item label="最大持仓数">
          <el-input-number v-model="editTarget.maxPositions" :min="1" :max="10" :step="1" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editTarget.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" @click="saveConfig" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getStrategyOverview, getStrategyPositions, getStrategyTrades,
  getStrategySignals, getStrategyDailyStats, getStrategyConfigs,
  updateStrategyConfig, closeStrategyPosition, triggerStrategyScan
} from '../api'

const loading = ref(false)
const scanning = ref(false)
const saving = ref(false)
let timer = null

// 概览数据
const overview = ref({})
const positions = ref([])
const trades = ref([])
const signals = ref([])
const dailyStats = ref([])
const configs = ref([])
const tradeFilter = ref('')

// 编辑弹窗
const configVisible = ref(false)
const editTarget = ref({})

const profitFactor = computed(() => {
  const maxWin = overview.value.maxWin || 0
  const maxLoss = Math.abs(overview.value.maxLoss || 1)
  if (maxLoss === 0) return '--'
  return (maxWin / maxLoss).toFixed(2)
})

async function refreshAll() {
  loading.value = true
  try {
    const [oRes, pRes, tRes, sRes, dRes, cRes] = await Promise.all([
      getStrategyOverview(),
      getStrategyPositions(),
      getStrategyTrades(tradeFilter.value),
      getStrategySignals(),
      getStrategyDailyStats(),
      getStrategyConfigs()
    ])
    resultOrEmpty(oRes, overview)
    resultOrEmpty(pRes, positions)
    resultOrEmpty(tRes, trades)
    resultOrEmpty(sRes, signals)
    resultOrEmpty(dRes, dailyStats)
    resultOrEmpty(cRes, configs)
    calcRunningTotals()
  } catch (e) {
    console.error('加载策略数据失败:', e)
  } finally {
    loading.value = false
  }
}

function calcRunningTotals() {
  let runningPnl = 0
  let maxRunningPnl = 0
  for (const t of trades.value) {
    runningPnl += (t.pnl || 0)
    if (runningPnl > maxRunningPnl) maxRunningPnl = runningPnl
  }
  overview.value.runningMaxPnl = maxRunningPnl
  overview.value.runningCurrentPnl = runningPnl
}

async function loadTrades() {
  const res = await getStrategyTrades(tradeFilter.value)
  resultOrEmpty(res, trades)
  calcRunningTotals()
}

async function scanNow() {
  scanning.value = true
  try {
    await triggerStrategyScan()
    ElMessage.success('扫描完成')
    await refreshAll()
  } catch (e) {
    ElMessage.error('扫描失败')
  } finally {
    scanning.value = false
  }
}

async function manualClose(row) {
  try {
    await ElMessageBox.prompt('平仓原因', '手动平仓', {
      inputValue: '手动平仓',
      confirmButtonText: '确认平仓',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await closeStrategyPosition(row.id, '手动平仓')
    ElMessage.success(`${row.baseAsset} 已平仓`)
    await refreshAll()
  } catch (e) {
    ElMessage.error('平仓失败')
  }
}

function editConfig(cfg) {
  editTarget.value = { ...cfg }
  configVisible.value = true
}

async function saveConfig() {
  saving.value = true
  try {
    await updateStrategyConfig(editTarget.value)
    ElMessage.success('配置已更新')
    configVisible.value = false
    await refreshAll()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleConfig(cfg) {
  try {
    await updateStrategyConfig({ ...cfg, enabled: !cfg.enabled })
    ElMessage.success(`${cfg.name} ${cfg.enabled ? '已停用' : '已启用'}`)
    await refreshAll()
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

function resultOrEmpty(res, refObj) {
  if (res && res.data && res.data.code === 0) {
    refObj.value = res.data.data || (Array.isArray(res.data.data) ? [] : {})
  }
}

onMounted(() => {
  refreshAll()
  timer = setInterval(refreshAll, 30000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.strategy-page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 概览卡片 */
.overview-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 12px;
}

.stat-card {
  text-align: center;
  padding: 8px 4px;
}
.stat-card :deep(.el-card__body) {
  padding: 12px 8px;
}

.stat-val {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
  margin-bottom: 4px;
}

.stat-val.accent { color: #409eff; }
.stat-val.up { color: #67c23a; }
.stat-val.down { color: #f56c6c; }

.stat-label {
  font-size: 12px;
  color: #909399;
}

/* 网格布局 */
.main-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 900px) {
  .main-grid { grid-template-columns: 1fr; }
}

.section-card {
  overflow: hidden;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
}

/* 策略参数 */
.params-wrapper {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.param-group {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  background: #fafafa;
}

.param-group.EARLY { border-left: 4px solid #67c23a; }
.param-group.HIGH { border-left: 4px solid #e6a23c; }

.param-title {
  font-size: 15px;
  font-weight: 700;
  color: #303133;
  margin-bottom: 10px;
}

.param-row {
  display: flex;
  justify-content: space-between;
  padding: 3px 0;
  font-size: 13px;
  color: #606266;
}

.param-row .up { color: #67c23a; }
.param-row .down { color: #f56c6c; }

.edit-btn {
  margin-top: 8px;
}

@media (max-width: 700px) {
  .params-wrapper { grid-template-columns: 1fr; }
}

/* 表格通用 */
.compact-table {
  font-size: 13px;
}

.up { color: #67c23a; font-weight: 600; }
.down { color: #f56c6c; font-weight: 600; }
</style>
