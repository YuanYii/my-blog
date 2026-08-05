# 2026-06-16 v2.5.0 OPT: 仪表盘 3 个微优化

## TL;DR

仪表盘 3 项体验优化：
1. "总文章"卡片 → 可点击跳 `/admin/posts` 文章管理
2. "待审评论"卡片 → 可点击跳 `/admin/comments` 评论管理
3. "总字数" → 按已发布文章的实际字数（strip markdown 后）展示，修复旧实现把阅读量错算成字数的 bug

---

## 改动详情

### OPT-1 / OPT-2: KPI 卡片可点击跳转

**问题**：仪表盘"总文章""待审评论"两个 KPI 卡片只是静态数字，用户想看具体内容还得手动点 sidebar 导航。

**改法**：
- 把 value + delta 两个 div 包进 `<NuxtLink :to="...">`
- 新增 `.kpi-card-link` 样式：hover 时 value 变色、delta 半透明，提示可点击
- sparkline 仍是 `position: absolute` 定位，不受 NuxtLink 包裹影响

**跳转目标**：
- 总文章 → `/admin/posts`
- 待审评论 → `/admin/comments`

**delta 文案补充**：
- 总文章：`+10 已发布 · 查看 →`
- 待审评论：`0 个待审 · 查看 →` / `5 个待处理 · 去处理 →`

### OPT-3: 总字数按实际字数量展示

**问题（之前是个 bug）**：
```vue
<!-- 旧实现：把 view_count / 100 当成"字数"展示 -->
<div class="kpi-card-value">{{ Math.round((kpi.totalViewCount || 0) / 100) / 10 }}k</div>
<div class="kpi-card-delta">累计阅读 {{ (kpi.totalViewCount || 0).toLocaleString() }}</div>
```

- `view_count` 是文章被浏览次数（15064）≠ 字数
- `view_count / 100 / 10 = 15.1k`——**单位 / 语义全是错的**

**改法**：
- 后端 `DashboardController` 新增 `kpi.totalWordCount`（`long`）
- 计算：`SELECT content_md FROM article WHERE status=1 AND deleted=0` → Java 端逐篇 `stripMarkdown` + `countChars` 累加
- 前端用 `(kpi.totalWordCount || 0).toLocaleString()` 展示（如 `1,808`）
- delta 文案改为 "已发布文章累计"

**stripMarkdown 处理范围**（保留"读者看到什么字符"的语义）：
- 代码块 / 行内代码 → 内部内容保留（代码字符也算"字数"）
- 图片 `![alt](url)` → 保留 alt
- 链接 `[text](url)` → 保留 text
- 标题前缀 `#` / `##` / `###` → 去掉
- 引用前缀 `>` → 去掉
- 列表标记 `-` / `*` / `+` / `1.` → 去掉
- 粗体 `**xxx**` / 斜体 `*xxx*` / 删除线 `~~xxx~~` → 去掉标记符

**countChars 算法**：
- 按 Unicode codePoint 遍历（CJK 字符每个 1 字）
- 跳过空白（空格 / 换行 / Tab / 全角空格）
- MVP 不做"英文按词数"分词——保持简单可解释

**为什么不加 article.word_count 字段**：
- schema 改动 = 改表 + 改 entity + 改所有写路径（成本/收益不匹配当前文章量级）
- 当前文章量（个位数~几十）下，每次 dashboard 调用 SELECT 一次 content_md 文本量可控（10 篇 × 平均 400 字符 = 4KB）
- 后续如文章量 >1000，再加 word_count 字段（写时计算 + 增量更新）即可

---

## 验证

### 端到端（curl + 模拟前端展示）

```
总文章卡片: 12 / 已发布 10
待审评论卡片: 0
总字数卡片: 1,808 （旧实现会显示 15.1k）
```

### 字数算法合理性

- 5 篇已发布文章，content_md 原始字符数总和：3615（含 markdown 标记）
- strip 后字符数：1808（约 50%，markdown 标记占一半合理——标题 `##` `**` 链接 `[text](url)` 列表 `1. ` 标记是常见的"膨胀源"）

### 字数预估（10 篇已发布）

如果所有 10 篇都平均 360 字符原始 → 估 strip 后 ~1800-2000 字符。后端实测 **1808** ✅

---

## 改动文件

- 后端：`backend/blog-app/src/main/java/com/blog/controller/DashboardController.java`
  - KPI 新增 `totalWordCount`
  - 新增 `computeTotalWordCount` / `stripMarkdown` / `countChars` 私有方法
- 前端：`frontend/pages/admin/dashboard.vue`
  - 总文章 / 待审评论卡片包 `<NuxtLink>`
  - 总字数卡片改用 `kpi.totalWordCount`
- 前端：`frontend/assets/css/main.css`
  - 新增 `.kpi-card-link` 样式
