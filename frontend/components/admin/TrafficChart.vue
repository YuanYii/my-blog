<script setup lang="ts">
/**
 * C（2026-06-20）：访问趋势折线图，从 dashboard.vue 的 renderCharts 抽出。
 */
import { fillDays, getThemeColors } from '~/composables/useDashboardUtils'

const props = defineProps<{
  visitTrend: any[]
  visitTrend7: any[]
}>()

const activeTab = ref('7')
let trendChart: any = null

const renderTrend = () => {
  if (!import.meta.client) return
  const Chart = (window as any).Chart
  if (!Chart) return
  const c = getThemeColors()
  const days = activeTab.value === '7' ? 7 : activeTab.value === '30' ? 30 : activeTab.value === '90' ? 90 : 365
  const source = days <= 7 ? props.visitTrend7 : props.visitTrend
  const pvSeries = fillDays(source, days, 'pv')
  const uvSeries = fillDays(source, days, 'uv')
  const xLabels: string[] = []
  const today = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(today.getDate() - i)
    xLabels.push(`${d.getMonth() + 1}/${d.getDate()}`)
  }
  if (trendChart) trendChart.destroy()
  const ctx = (document.getElementById('chart-trend') as any)?.getContext('2d')
  if (!ctx) return
  trendChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels: xLabels,
      datasets: [
        {
          label: 'PV', data: pvSeries,
          borderColor: c.primary, backgroundColor: 'rgba(47,111,94,0.10)',
          borderWidth: 2, fill: true, tension: 0.35, pointRadius: 0, pointHoverRadius: 5
        },
        {
          label: 'UV', data: uvSeries,
          borderColor: c.accent, backgroundColor: 'transparent',
          borderWidth: 1.5, borderDash: [4, 3], fill: false, tension: 0.35, pointRadius: 0, pointHoverRadius: 5
        }
      ]
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      plugins: {
        legend: { position: 'top', align: 'end', labels: { boxWidth: 8, boxHeight: 8, usePointStyle: true, font: { size: 11 }, color: c.muted } },
        tooltip: { backgroundColor: '#1a1f2e', padding: 10, cornerRadius: 8 }
      },
      scales: {
        x: { grid: { display: false }, ticks: { color: c.muted, font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 8 } },
        y: { grid: { color: c.grid, drawBorder: false }, ticks: { color: c.muted, font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 5 }, beginAtZero: true }
      },
      interaction: { mode: 'index', intersect: false }
    }
  })
}

defineExpose({ renderTrend })

watch(activeTab, () => { if (trendChart) renderTrend() })
</script>

<template>
  <div class="panel">
    <div class="panel-header">
      <h3 class="panel-title">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
        访问趋势
      </h3>
      <div class="chart-tabs">
        <button v-for="t in ['7', '30', '90', '今年']" :key="t"
          class="chart-tab" :class="{ active: activeTab === t }"
          @click="activeTab = t">{{ t }}{{ t === '今年' ? '' : ' 天' }}</button>
      </div>
    </div>
    <div class="chart-area">
      <canvas id="chart-trend"></canvas>
    </div>
  </div>
</template>
