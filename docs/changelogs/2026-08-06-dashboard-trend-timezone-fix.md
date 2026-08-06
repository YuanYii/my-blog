# Changelog · 2026-08-06

## 仪表盘访问趋势图隔天日期与数据错位修复 (20260806-BUG-001)

### 问题描述
在东八区（UTC+8）环境下的凌晨时间段（00:00 - 07:59）访问管理后台仪表盘时，折线图 X 轴显示的日期标签为当天，但匹配填入的 PV/UV 数值却是昨日数据，导致整条趋势折线产生 1 天的错位。

### 根因分析
- 前端 `TrafficChart.vue` 组件采用浏览器本地时区生成 X 轴标签（例如 `8/6`）。
- 工具函数 `useDashboardUtils.ts` 中的 `fillDays` 在对 Date 对象进行日期 Key 提取时，调用了 `d.toISOString().substring(0, 10)`。
- `d.toISOString()` 强制输出 UTC 零时区时间，凌晨转换后仍落入昨日日期 `YYYY-MM-DD`，造成提取的数据匹配 Key 漂移。

### 修复方案
- 修改 `frontend/composables/useDashboardUtils.ts`，新增 `toLocalDateString` Helper 函数（提取本地 `getFullYear()`, `getMonth() + 1`, `getDate()` 拼接字符串）。
- 在 `fillDays` 补全日期逻辑中，替换原有的 `toISOString` 截取方式，使数值 Key 与 X 轴标签的时区绝对保持一致。

### 涉及文件
- `frontend/composables/useDashboardUtils.ts`
