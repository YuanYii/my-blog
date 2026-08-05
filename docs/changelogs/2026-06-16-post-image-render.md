# 2026-06-16 v2.3.1 文章详情页图片渲染修复

## TL;DR

用户报告：文章预览界面（`/post/[slug]`）显示图片链接而非图片。
排查发现：`frontend/pages/post/[slug].vue` 的 `renderMarkdown` 没处理 `![alt](url)` 语法（漏改），DOMPurify 白名单也漏了 `img`。

修复：
- `renderMarkdown` 加图片语法正则（放在普通链接**之前**，否则链接正则会先匹配掉）
- DOMPurify `ALLOWED_TAGS` 加 `img`，`ALLOWED_ATTR` 加 `src/alt/loading`

## 问题诊断

### 症状

用户写 `![pasted-xxx.png](http://localhost:8080/api/v1/uploads/2026/06/xxx.png)` 进文章 markdown。
详情页渲染出 `<p>!<a href="http://..." target="_blank">pasted-xxx.png</a></p>`——文字链接而非图片。

### 根因

`frontend/pages/post/[slug].vue` 的 `renderMarkdown` 函数（line 102-125）只处理：
- 代码块、标题、粗体/斜体、**链接** `[text](url)`、引用、列表、段落

**没有图片语法** `![alt](url)`。当用户写图片 markdown 时：
1. 链接正则 `\[([^\]]+)\]\(([^)]+)\)` **会**匹配 `![alt](url)` 内部的 `[alt](url)` → 转成 `<a href="url">alt</a>`
2. 留下前面的 `!` 当纯文本
3. 最终输出 `<p>!<a href="url">alt</a></p>`

而且双层保险失效：DOMPurify 的 `ALLOWED_TAGS` 里**没有 `img`**——即使前面有代码生成 `<img>`，也会被净化掉。

### 历史教训

同样的 bug **2026-06-15 v2.2.0 在 `admin/edit.vue` 修过**（见 `docs/changelogs/2026-06-15-image-upload-fix.md` / AGENTS.md §12.10）。
但当时只改了 admin 编辑器预览，**漏改了文章详情页**——两个文件的 `renderMarkdown` 是独立的实现，没共享。

**教训**：项目里有"两套 markdown 渲染器"是历史包袱（admin 编辑器走简版，详情页走简版，生产待升级 marked/remark）。下次任何 markdown 语法支持都得**同步两个文件**，不能只改一处。

## 修复

### 1. `frontend/pages/post/[slug].vue:102-125` `renderMarkdown`

在普通链接正则**之前**加图片语法正则（顺序敏感——图片正则必须先匹配）：

```js
// 图片语法：必须在普通链接前匹配
// alt 文本里允许空：![](url)，url 允许双引号包起来：![alt]( "url" )
html = html.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g,
  '<img src="$2" alt="$1" loading="lazy" />')
// 链接
html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>')
```

### 2. `frontend/pages/post/[slug].vue:156-160` DOMPurify 白名单

```js
// 加 img 标签
ALLOWED_TAGS: [..., 'img'],
// 加 src/alt/loading 属性
ALLOWED_ATTR: [..., 'src', 'alt', 'loading'],
```

### 3. `ssrSafeHtml` 兜底

不变。检查过：协议过滤（`javascript:/data:/vbscript:`）→ `<img src="..." onerror="...">` 的 onerror 会被 on* 事件过滤去掉 → 安全。

## 验证

**修复前**（node 复现旧逻辑）：
```
<p>!<a href="http://localhost:8080/api/v1/uploads/2026/06/20260616-444fcba3.png" target="_blank">pasted-1781606843010.png</a></p>
```

**修复后**（详情页 SSR HTML，curl 抓的）：
```
<img src="http://localhost:8080/api/v1/uploads/2026/06/20260616-444fcba3.png" alt="pasted-1781606843010.png" loading="lazy" />
```

**测试步骤**：
1. 用 pymysql 临时把 `article.id=1` 的 content_md 改成"图1+图2"的测试内容
2. `curl http://localhost:3000/post/spring-boot-jwt-security` 抓 SSR HTML
3. 正则匹配 `<div class="prose">` 内部 → 2 个 `<img>` 标签、0 个图片相关 `<a>` 标签 ✓
4. 恢复原文，curl 再抓一次 → 1 个 `<img>` 标签（原文只有 1 张图）✓

**截图存档**：`docs/audits/2026-06-16-post-image-fix/ssr-html-*.html`（两份：测试内容版 + 原始内容版）

## 影响范围

- 文章详情页（`/post/[slug]`）所有带图片 markdown 的文章
- 不影响纯文字文章、代码块、链接等其他渲染
- 修复路径与 `edit.vue` 2026-06-15 修复完全一致——以后两个文件 markdown 渲染保持同步

## 部署注意

前端 dev server HMR 自动生效；prod build 需要 `npm run build` 重 build。
无 DB 变更、无 API 变更。
