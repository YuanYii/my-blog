# 2026-07-01 BUG-001 前端闭环 — `search.vue` get 调用去掉 `params:` 包装

> Hotfix：6 月 30 日 BUG-001 后端 / nginx / search.vue 三段修复在 myblog-sim 容器实测时发现前端段未真正生效，本次补上前端 build 重打 + 部署。
> 版本号不变（仍 v5.0.0），故不写 `vX.Y.Z` 形式。

## TL;DR

BUG-001 修复报告（autodev/auto_audit/20260630/）里写"npm run build 成功 + 前端新 build 已部署到 myblog-sim"——**事实未真**：本地 `frontend/.output/public/` 时间戳停在 2026-06-30 23:33（OPT-002 时的 build），容器内 `/opt/myblog/frontend/_nuxt/` 停在 2026-06-30 15:33（更早），都没到 BUG-001 修复时间 23:48。

但**根因不是 build 没重打**——是 `search.vue` 的 `get` 调用从 OPT-002 引入时就错了：

```ts
// 错：query 对象被多包一层 params:，ofetch 把它序列化为
// ?params={"keyword":"刷量","page":1,"size":20}
// 后端拿不到 keyword → 走「无 keyword 列表」→ 返全表 6 条
const res = await get<any>('/articles', {
  params: { keyword: keyword.value, page: page.value, size }
})

// 对比 tags.vue 正确写法（直接展开 query 对象）
const res = await get<any>('/articles', { tagId: newTag, size: 50 })
```

`usePublicApi.get(path, queryParamsObj)` 的第二个参数**就是 query 对象本身**（实现 `get: (a, l) => n(a, { method: 'GET', params: l })`，`l` 整个当 params 值），search.vue 多写了一层 `params:` 包装 → query string 序列化成 JSON → 后端取不到 `keyword`。

## 排查链

| 步骤 | 工具 | 结论 |
|---|---|---|
| 1. playwright 打开 `localhost:28000/search?q=刷量` | playwright | 页面：6 条结果 + 无「建议缩小关键词范围」提示；网络：`?params={"keyword":"刷量",...}` |
| 2. `curl /api/v1/articles?keyword=刷量` | curl | 200，**1 条**精准命中（id 8231）—— 后端 BUG-001 修复完全正常 |
| 3. `curl /api/v1/articles?keyword=AI` | curl | 200，1 条；`?keyword=S` → 400「关键词至少 2 个字符」—— 后端全过 |
| 4. 容器内 grep `_nuxt/*.js` | grep | 含 `建议缩小关键词范围` 字符串、`isResultTooMany` 编译进去 —— BUG-001 前端代码在的 |
| 5. 容器内 grep `_nuxt/*.js` 看实际请求 | grep | `get: (a, l) => n(a, { method: "GET", params: l })` —— `l` 整个当 query params |
| 6. 对比 `tags.vue` 调用方式 | grep | `get('/articles', { tagId, size })` 直接展开 —— search.vue 是唯一 `{ params: {...} }` 包装的 |
| 7. 本地 grep 新 build | grep | 改后 build 是 `await h("/articles",{keyword,page,size})` —— 错版包装消失 |
| 8. docker cp 新 build 到容器 + playwright 复跑 | docker + playwright | 1 条精准命中 + title 变「搜索：刷量」—— 修复生效 |

## 改动

### 1. `frontend/pages/search.vue`（1 行）

```diff
-    const res = await get<any>('/articles', {
-      params: { keyword: keyword.value, page: page.value, size }
-    })
+    const res = await get<any>('/articles', {
+      keyword: keyword.value, page: page.value, size
+    })
```

只改这一行，不动 `usePublicApi` / `useApi` 公共组件（tags.vue 已用对的写法，公共组件不该为单个页面屈就）。

### 2. 前端 build + 部署

- `cd frontend && npm run build`（`fetch-routes && nuxt generate`）—— 8 routes 预渲染（含 /search）
- `docker cp frontend/.output/public/. myblog-sim:/opt/myblog/frontend/` —— 新 build 拷进容器
- 旧 frontend 备份：`/opt/myblog/frontend.20260701-013812.bak`（948K）

## 修复后实测（myblog-sim 容器内 playwright）

| Case | 网络请求 | 页面显示 |
|---|---|---|
| `?q=刷量` | `?keyword=刷量&page=1&size=20` 200 | `共 1 条结果` —— 1C2G 抗 IP 刷量 |
| `?q=AI` | `?keyword=AI&page=1&size=20` 200 | `共 3 条结果` —— AI 系列一/二/三 |
| `?q=S` | `?keyword=S&page=1&size=20` 400 | `共 0 条结果` + `没有找到与「S」相关的文章`（usePublicApi catch 把 400 转成友好提示，不属本次 BUG） |

## 不动清单

- `usePublicApi.ts` / `useApi.ts` / `useAdminApi.ts` —— 公共组件不重构
- `tags.vue` / `index.vue` / `archives.vue` 等其他 `usePublicApi` 调用方 —— 都已用对的 `{ key, value }` 展开写法
- 后端 `ArticleService` / `ArticleController` —— BUG-001 修复段实测正常不动
- `deploy-server.sh` nginx 段 —— BUG-001 修复已生效不动
- `status.json` 老 stage message —— 历史追溯不改，仅追加新 stage message 记录本次 hotfix

## 反思

1. **Stage 5 集成测试的盲点**：上次 BUG-001 阶段 5 集成测试是**纯 curl 测后端 + 直接打 API 路径**，没有用 playwright 跑真实前端页面。curl 测后端 OK 报告「10 通过 / 0 失败」——但前端 query string 拼错这件事是 playwright 看真实网络面板才发现的。**Stage 5 必须含至少 1 个 playwright 真实浏览器走查**（不只是 curl）。
2. **build 时间戳核对应在 stage 2 收尾**：fix 完代码后理应 `ls -la frontend/.output/public/` 跟源代码 mtime 对一下，发现 23:33 vs 23:48 的差异就该知道 build 没重打。本次补做（核对本地 + 容器内 build 时间戳都跟 BUG-001 修复时间不符）。
3. **报告措辞要可验证**：「npm run build 成功」+「前端新 build 已部署」是双行断言，下次类似报告拆成两行分别 verify：`stat frontend/.output/public/` + `stat myblog-sim:/opt/myblog/frontend/_nuxt/` 时间戳都 ≥ 修复时间才算 PASS。

## 关联

- BUG-001 任务卡：`autodev/auto_iteration/20260630.md`（已追加本次前端闭环段）
- BUG-001 Stage 报告：`autodev/auto_audit/20260630/20260630-stage3.md` / `-stage4.md` / `-stage5.md`（历史快照，不改）
- status.json：追加本轮 hotfix message
