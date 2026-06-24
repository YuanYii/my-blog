<script setup lang="ts">
/**
 * C（2026-06-20）：分类分布饼图，从 dashboard.vue 的 renderCharts 抽出。
 */
import { getThemeColors } from '~/composables/useDashboardUtils'

const props = defineProps<{
  categoryDist: any[]
}>()

let categoryChart: any = null

const renderCategory = () => {
  if (!import.meta.client) return
  const Chart = (window as any).Chart
  if (!Chart || !props.categoryDist.length) return
  const c = getThemeColors()
  if (categoryChart) categoryChart.destroy()
  const ctx = (document.getElementById('chart-category') as any)?.getContext('2d')
  if (!ctx) return
  const labels = props.categoryDist.map(c => c.name)
  const data = props.categoryDist.map(c => c.cnt)
  const colors = ['#2f6f5e', '#c97b3f', '#3b82f6', '#8b5cf6', '#94a3b8', '#ec4899', '#10b981']
  categoryChart = new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels,
      datasets: [{ data, backgroundColor: labels.map((_, i) => colors[i % colors.length]), borderWidth: 0, spacing: 2 }]
    },
    options: {
      responsive: true, maintainAspectRatio: false, cutout: '70%',
      plugins: {
        legend: { position: 'right', labels: { boxWidth: 8, boxHeight: 8, usePointStyle: true, font: { size: 11 }, color: c.text, padding: 8 } }
      }
    }
  })
}

defineExpose({ renderCategory })
</script>

<template>
  <div class="dashboard-category">
    <div class="panel-header">
      <h3 class="panel-title">分类分布</h3>
      <NuxtLink to="/admin/categories" class="panel-action">分类管理</NuxtLink>
    </div>
    <div class="chart-area chart-area-category">
      <canvas v-if="categoryDist.length" id="chart-category"></canvas>
      <div v-else class="chart-empty">暂无数据</div>
    </div>
  </div>
</template>
