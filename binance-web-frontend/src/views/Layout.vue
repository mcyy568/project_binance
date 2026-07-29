<template>
  <el-container class="layout-container">
    <!-- 左侧菜单 -->
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon :size="28"><Coin /></el-icon>
        <span>币安行情系统</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#1d1e2b"
        text-color="#aeb2c4"
        active-text-color="#409EFF"
        class="menu"
      >
        <el-menu-item index="/coins">
          <el-icon><List /></el-icon>
          <span>币种列表</span>
        </el-menu-item>
        <el-menu-item index="/favorites">
          <el-icon><Star /></el-icon>
          <span>我的收藏</span>
        </el-menu-item>
        <el-menu-item index="/strategy">
          <el-icon><SetUp /></el-icon>
          <span>策略交易</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 右侧内容 -->
    <el-container>
      <el-header class="header">
        <span class="header-title">{{ pageTitle }}</span>
        <div class="header-right">
          <el-input
            v-model="quickSymbol"
            placeholder="输入币名查看K线，如 BTC"
            clearable
            :prefix-icon="Search"
            class="quick-input"
            @keyup.enter="goToKline"
          >
            <template #append>
              <el-button :icon="TrendCharts" @click="goToKline">查看K线</el-button>
            </template>
          </el-input>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Coin, List, Star, Search, TrendCharts, SetUp } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const activeMenu = computed(() => route.path)
const pageTitle = computed(() => route.meta?.title || '币安行情')
const quickSymbol = ref('')

function goToKline() {
  const input = quickSymbol.value.trim().toUpperCase()
  if (!input) return
  // 自动补全 USDT 后缀
  const symbol = input.endsWith('USDT') ? input : input + 'USDT'
  quickSymbol.value = ''
  router.push({ name: 'CoinDetail', params: { symbol } })
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.aside {
  background-color: #1d1e2b;
  overflow: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #409EFF;
  font-size: 18px;
  font-weight: bold;
  border-bottom: 1px solid #2d2e3e;
}

.menu {
  border-right: none;
}

.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
}

.header-right {
  display: flex;
  align-items: center;
}

.quick-input {
  width: 320px;
}

.main {
  background: #f5f7fa;
}
</style>
