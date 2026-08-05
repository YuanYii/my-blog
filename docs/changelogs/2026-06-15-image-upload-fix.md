# 2026-06-15 v2.2.0 图片上传 + Markdown 粘贴全面修复

## TL;DR

用户报告："头像上传无法使用；文章编辑的 markdown 界面中也无法直接粘贴图片"。
排查后实际是 **4 个独立问题** 串联在一起，全部修复。

- **修 1（关键 bug）**：上传 URL 不带 `/api/v1` context-path，导致 GET 静态资源 404
- **修 2（核心功能）**：markdown 编辑器增加粘贴 / 本地选择 / 拖拽图片上传
- **修 3（连带 bug）**：markdown 预览渲染器不支持 `![alt](url)` 图片语法
- **修 4（隐藏 bug）**：`updateProfile` 用 `updateById` 全字段更新，会覆盖 `password_hash`

---

## 问题诊断

### 1. UploadController 返回的 url 不带 context-path

**症状**：上传接口 `POST /api/v1/admin/uploads` 跑通，文件写盘成功，前端拿到 `url`，但 `<img src>` 显示图片 404。

**根因**：
- `application.yml` 配了 `server.servlet.context-path: /api/v1`
- `StaticResourceConfig` 用 `addResourceHandler("/uploads/**")` 注册静态资源，**handler 注册在 servlet 根 URL**
- 但 `UploadController.upload()` 返回的 url 是 `request.getScheme() + "://" + host + ":" + port + "/uploads/" + ...`
- 没有拼 `request.getContextPath()` → 前端拿到的是 `http://localhost:8080/uploads/...`（不带 /api/v1）→ GET 404

**证据**：
```
http://localhost:8080/uploads/2026/06/xxx.png           → 404
http://localhost:8080/api/v1/uploads/2026/06/xxx.png    → 200
```

**修复**（`backend/blog-settings/.../controller/UploadController.java`）：
```java
String contextPath = request.getContextPath();  // "/api/v1"
if (contextPath.endsWith("/")) contextPath = contextPath.substring(0, contextPath.length() - 1);
String url = origin + contextPath + "/uploads/" + yearMonth + "/" + name;
```

### 2. markdown 编辑器没图片粘贴 / 选择上传能力

**症状**：编辑器只有 toolbar 一个图片按钮 `prompt('图片 URL')`，粘贴图片到 textarea 完全没反应。

**根因**：原 `insertImagePrompt` 仅支持 prompt 输入 URL，没有任何处理剪贴板图片 / file picker / 拖拽的 handler。

**修复**（`frontend/pages/admin/edit.vue`）：
- toolbar 图片按钮 → 打开 hidden `<input type="file" accept="image/*" multiple>`
- `@paste` 监听 textarea → 截获剪贴板图片 → 走统一上传入口
- `@drop` + `@dragover` 监听 textarea → 拖拽图片文件 → 走统一上传入口
- `insertImageAtCursor(file)` 统一入口：先插占位 `![uploading-xxx.png…]()` → 异步上传 → 完成后 `String.replace` 把占位换成 `![alt](http://...)`
- toolbar 右侧加 `uploadingImages` 计数提示（spinner 动画）
- 大小校验：> 5MB 拒；非 image/* 拒

**关键设计**：
- 多文件并发：每次插入独立的 marker，互不干扰
- 占位 marker 唯一（带文件名）→ 上传完成精确 replace
- 失败也清占位，避免残留半截 markdown

### 3. markdown 预览渲染器不支持图片语法

**症状**：textarea 里 `![alt](url)` 是对的，但右侧预览显示成 `<p>!<a>alt</a></p>`（! 当文本，剩下当链接）。

**根因**：`renderMarkdown()` 里只有 `\[([^\]]+)\]\(([^)]+)\)` 处理普通链接，没有先匹配图片语法 `!\[...\]\(...\)`。

**修复**（`frontend/pages/admin/edit.vue`）：
```js
// 必须在普通链接前匹配（图片也是 ![](url)，正则会被普通链接先吃掉）
html = html.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g,
  '<img src="$2" alt="$1" loading="lazy" />')
html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>')
```
+ DOMPurify 白名单加 `img` tag + `src`/`alt`/`loading` attr。

### 4. updateProfile / updatePassword 用 updateById 全字段更新

**症状**：用户改密后保存 profile，密码又被覆盖回旧 hash，登不上。

**根因**：
- MyBatis-Plus `BaseMapper.updateById(entity)` 默认 **全字段更新**，把所有 entity 字段都写一遍
- `updateProfile` 走 `selectById(1L)` + 字段级 setter + `updateById(user)` —— 即使没动 password_hash，select 出来的 password_hash 值会被一并写回
- 更糟：如果将来 body 里出现 `password_hash` 字段（前端 bug / 中间人 / 误传），会被原样写库

**修复**（`backend/blog-settings/.../controller/SettingsController.java`）：
改用 `UpdateWrapper<User>` + `.set("col", val)` 严格白名单方式：
```java
UpdateWrapper<User> uw = new UpdateWrapper<>();
uw.eq("id", 1L);
if (body.containsKey("nickname")) { uw.set("nickname", ...); changed = true; }
// email / avatar / bio / location 同理
if (!changed) return Result.success();
userMapper.update(null, uw);  // entity=null，走 wrapper SQL
```
`updatePassword` 同样改写 —— 只 set `password_hash` + `updated_at`，不动其他列。

**防御收益**：
- 即使前端 body 里塞 `password_hash` / `role` / `username` 等敏感字段，后端直接 ignore
- 多线程场景下 select-then-update 的 race condition 也消失（不再读 user 对象）

---

## 验证

### 端到端

用 Playwright + 真 admin token + approved device 测试：

| 场景 | 旧行为 | 新行为 |
|---|---|---|
| 头像点击 → 选本地 PNG | 200 + url `http://localhost:8080/uploads/...` → img 404 | 200 + url `http://localhost:8080/api/v1/uploads/...` → img 200 |
| markdown toolbar 图片按钮 → 选 PNG | 仅支持 prompt URL | 弹 file picker，上传后插入 `![name](url)` |
| markdown textarea 粘贴图片 | 完全无反应（被浏览器默认吞掉） | 截获 → 上传 → 插入占位 → 替换成 `![name](url)` |
| markdown textarea 拖拽 PNG | 无 handler | 截获 → 同上 |
| 预览渲染 `![alt](url)` | `<p>!<a>alt</a></p>` | `<img src="url" alt="alt" loading="lazy">` |
| 改密后 PUT /admin/settings/profile 带恶意 `password_hash` 字段 | hash 被覆盖，登录失败 | hash 不被覆盖，登录 OK |

### 截图存档

`docs/audits/2026-06-15-image-upload/`：
- `editor-with-2-images.png`：markdown 编辑器，左侧 textarea 含两条 markdown 引用，右侧预览渲染了 2 张图（一张从 file picker 选、一张从剪贴板粘贴）
- `settings-avatar.png`：个人资料 tab，圆形 Y 头像预览正确显示，URL 输入框同步填了 `/api/v1/uploads/...` 路径

### curl 验证

```bash
# 上传
$ curl -X POST http://localhost:8080/api/v1/admin/uploads \
    -H "Authorization: Bearer $TOKEN" -H "X-Device-Id: $DID" \
    -F "file=@/tmp/_avatar.png"
{"code":200,"data":{"size":72,"name":"20260615-xxx.png",
 "url":"http://localhost:8080/api/v1/uploads/2026/06/20260615-xxx.png"}}

# GET 拿图
$ curl -I http://localhost:8080/api/v1/uploads/2026/06/20260615-xxx.png
HTTP/1.1 200
Content-Type: image/png;charset=UTF-8
```

---

## 文件改动清单

| 文件 | 改动 |
|---|---|
| `backend/blog-settings/.../controller/UploadController.java` | 返回 url 拼 `request.getContextPath()`，更新注释（去掉"前端能直 GET"的错误假设） |
| `frontend/pages/admin/edit.vue` | 新增 `mdImageInput`/`uploadingImages`/`insertImageAtCursor`/`uploadAndInsertImage`/`handleMdImageFileChange`/`handleEditorPaste`/`handleEditorDrop`/`handleEditorDragOver`；toolbar 加 hidden file input；textarea 加 `@paste`/`@drop`/`@dragover`；`renderMarkdown` 加图片语法支持；DOMPurify 白名单加 `img` |
| `frontend/assets/css/main.css` | 加 `@keyframes spin` 动画 + `.upload-progress` 样式 |
| `backend/blog-settings/.../controller/SettingsController.java` | `updateProfile` + `updatePassword` 改用 `UpdateWrapper` 白名单更新；加 `LocalDateTime` + `UpdateWrapper` import |

---

## 教训沉淀

1. **`server.servlet.context-path` 会让所有静态资源 URL 路径偏移**——任何返回绝对 URL 的 controller 必须用 `request.getContextPath()` 拼，不能手写 `/uploads/`。这个坑不显式测试 GET 就发现不了（POST 看响应 code 200 就以为 OK）。
2. **MyBatis-Plus `updateById(entity)` 是全字段更新，不是按改动列更新**。如果要"只更新部分字段"，必须用 `UpdateWrapper` + `.set(...)`。涉及敏感字段（密码、role）的 entity 必须改用 wrapper 模式。
3. **markdown 简版渲染器也要支持图片语法**——用户编辑器能正常生成 `![alt](url)`，但渲染器不支持的话预览和详情页都会显示错。同一份 markdown 渲染器要在编辑器预览 + 详情页 + RSS 输出等多处复用。