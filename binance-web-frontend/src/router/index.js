import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../views/Layout.vue'
import CoinList from '../views/CoinList.vue'
import CoinDetail from '../views/CoinDetail.vue'
import Favorites from '../views/Favorites.vue'
import StrategyDashboard from '../views/StrategyDashboard.vue'

const routes = [
  {
    path: '/',
    component: Layout,
    redirect: '/coins',
    children: [
      {
        path: 'coins',
        name: 'CoinList',
        component: CoinList,
        meta: { title: '币种列表' }
      },
      {
        path: 'favorites',
        name: 'Favorites',
        component: Favorites,
        meta: { title: '我的收藏' }
      },
      {
        path: 'strategy',
        name: 'StrategyDashboard',
        component: StrategyDashboard,
        meta: { title: '策略交易' }
      },
      {
        path: 'coins/:symbol',
        name: 'CoinDetail',
        component: CoinDetail,
        meta: { title: 'K线详情' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
