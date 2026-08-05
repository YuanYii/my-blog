# 2026-06-16 v2.3.0 评论删除修复 + 设备自删防护

## TL;DR

用户报告：
1. 评论管理中"删除评论"按钮点击后报错"删除失败：..."
2. 设备管理中允许删除当前登录设备的授权（应该禁止）

两个 bug 已全部修复并通过端到端自测。

---

## 修复 1（BUG-076）：评论物理删除

### 症状

`/admin/comments` 页面点"删除"按钮，前端弹 `删除失败：del is not defined`。

### 根因

`frontend/pages/admin/comments.vue:4` 之前的解构：

```ts
const { get, put } = useAdminApi()
```

漏了 `del` —— `handleDelete` 调 `del('/comments/${c.id}')` 时 `del` 是 undefined → 抛 `ReferenceError` → catch 弹错。

后端 `DELETE /api/v1/comments/{id}` 早就实现了，**只缺前端解构**。

### 修复

```ts
// frontend/pages/admin/comments.vue
const { get, put, del } = useAdminApi()
```

### 验证

```
DELETE /api/v1/comments/32 → 200 OK
DELETE /api/v1/comments/32 → 1001 "评论不存在"（重复删兜底）
```

---

## 修复 2（BUG-077）：禁止删除当前登录设备

### 症状

`/admin/devices` 页面允许 admin 删除自己当前正在用的设备记录。删除后：
- 后端 `admin_device` 表那行没了
- 下次 admin API 请求的 `X-Device-Id` 找不到记录 → 401 → 强制踢回登录页
- 体感是"我刚做完一个操作，下一次操作就被踢出来了"

### 根因

2026-06-13 v2.2.0 实现"物理删除"功能时，业务策略定的是"不阻止自删，靠 confirm 二次确认防误操作"。但用户本次明确反对这个策略。

同时 `DeviceService.delete` 之前没有任何防护逻辑，与 `revoke`（已防自吊销）行为不一致——安全模型被打破。

### 修复（双层防护）

**层 1：前端 UI 禁用**

`frontend/pages/admin/devices.vue`：
- 删除按钮加 `:disabled="d.deviceId === myDeviceId"`
- 禁用样式：opacity 0.4 + cursor not-allowed
- title 提示改为"不能删除当前登录设备（请在另一台已授权设备上操作）"
- `remove()` 函数前再加一道防御性 if 判断（UI bypass 兜底）
- 底部"工作流程"说明文案更新，明确"当前登录设备被禁用删除/吊销按钮"
- 拦截 `code === 2003` 时给用户友好提示（与 revoke 共用错误码）

**层 2：后端兜底**

`backend/blog-auth/.../service/DeviceService.java`：

```java
public void delete(Long id, String currentDeviceId) {
    AdminDevice device = deviceMapper.selectById(id);
    if (device == null) throw new BusinessException(ResultCode.NOT_FOUND);
    // 禁止自删——与 revoke 完全一致的安全模型
    if (currentDeviceId != null && !currentDeviceId.isEmpty()
            && currentDeviceId.equals(device.getDeviceId())) {
        throw new BusinessException(ResultCode.DEVICE_SELF_REVOKE_FORBIDDEN);
    }
    deviceMapper.deleteById(id);
}
```

`backend/blog-common/.../ResultCode.java`：
- `DEVICE_SELF_REVOKE_FORBIDDEN(2003)` 的 message 改为 `"不能吊销/删除当前登录设备"`，覆盖两种自我解绑场景

### 验证

```
DELETE /api/v1/admin/devices/36  (id=36 = 当前 device)  → 2003 "不能吊销/删除当前登录设备"  ✓
DELETE /api/v1/admin/devices/39  (id=39 = 别人设备)     → 200 OK                              ✓
```

前端 UI 自测：当前设备的删除按钮 opacity 0.4、cursor not-allowed、hover title 显示禁用原因。

---

## 影响范围

- **评论删除**：原 UI 行为恢复（之前点了没反应，看起来"功能坏了"）
- **设备删除**：策略收紧——与"吊销"一致，admin 不能把自己踢出系统

## 部署注意

无 DB schema 变更、无 API 契约变更、无新依赖。后端需 `mvn -pl blog-common,blog-auth -am install` 重启（按 §12.6 教训）。
