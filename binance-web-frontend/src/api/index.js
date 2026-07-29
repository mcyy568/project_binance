import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 获取所有币种
export function getCoins() {
  return api.get('/coins')
}

// 获取K线数据
export function getKlines(symbol) {
  return api.get(`/klines/${symbol}`)
}

// 获取推荐列表
export function getRecommendations() {
  return api.get('/recommendations')
}

// 手动触发扫描
export function triggerScan() {
  return api.post('/recommendations/scan')
}

// 获取收藏列表
export function getFavorites() {
  return api.get('/favorites')
}

// 添加收藏（body: { baseAsset, direction, score, price, reason, volume, change24h }）
export function addFavorite(symbol, data) {
  return api.post(`/favorites/${symbol}`, data)
}

// 取消收藏
export function removeFavorite(symbol) {
  return api.delete(`/favorites/${symbol}`)
}

// 检查是否已收藏
export function checkFavorite(symbol) {
  return api.get(`/favorites/${symbol}/exists`)
}

// 手动计算单个币种评分
export function scoreCoin(symbol) {
  return api.post(`/coins/${symbol}/score`)
}

// ==================== 策略交易系统 ====================

// 获取策略概览
export function getStrategyOverview() {
  return api.get('/strategy/overview')
}

// 获取当前持仓
export function getStrategyPositions() {
  return api.get('/strategy/positions')
}

// 获取交易记录
export function getStrategyTrades(filter) {
  return api.get('/strategy/trades', { params: { filter } })
}

// 获取信号记录
export function getStrategySignals() {
  return api.get('/strategy/signals')
}

// 获取每日统计
export function getStrategyDailyStats() {
  return api.get('/strategy/daily-stats')
}

// 获取策略配置
export function getStrategyConfigs() {
  return api.get('/strategy/configs')
}

// 更新策略配置
export function updateStrategyConfig(config) {
  return api.put('/strategy/configs', config)
}

// 手动平仓
export function closeStrategyPosition(id, reason) {
  return api.post(`/strategy/positions/${id}/close`, { reason })
}

// 手动触发扫描
export function triggerStrategyScan() {
  return api.post('/strategy/scan')
}

// 获取监控列表
export function getStrategyWatchlist() {
  return api.get('/strategy/watchlist')
}

// 更新监控列表
export function updateStrategyWatchlist(list) {
  return api.put('/strategy/watchlist', list)
}

export default api
