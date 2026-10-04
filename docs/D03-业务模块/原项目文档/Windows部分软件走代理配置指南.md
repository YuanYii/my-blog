# Windows 按软件分流代理配置指南

> 目标：让 **A 软件走代理**，**B 软件不走代理**（直连）。
> 方案：sing-box（三文鱼/Salmon 底层内核）TUN 模式 + 进程路由规则 `process_name` / `process_path`。

---

## 1. 结论速览

| 项目 | 说明 |
|---|---|
| 能否实现 | ✅ 能。sing-box 路由规则 `process_name`/`process_path` 官方支持 **Linux / Windows / macOS** |
| Windows 硬前提 | **必须以管理员身份运行**（TUN 需创建虚拟网卡、改路由/防火墙） |
| 核心原理 | TUN 接管全部流量 → 路由规则按**进程名**匹配 → 命中进程走对应出站（代理 / 直连） |
| 不依赖 | 不依赖各软件是否支持自定义代理（全局接管） |

---

## 2. 机制原理

```
应用层                          sing-box（TUN 虚拟网卡）
┌──────────┐                  ┌──────────────────────────────┐
│  A 软件   │─── 全部流量 ───▶ │  ① process_name 匹配进程      │
│  B 软件   │                  │  ② 命中 → 代理出口 / 直连出口  │
│  其他软件  │                  │  ③ 未命中 → 后续规则/默认出口   │
└──────────┘                  └──────────────────────────────┘
```

- **TUN 模式**：创建虚拟网卡接管系统所有应用的流量（与系统代理不同，系统代理只对"读取系统代理设置"的软件生效，游戏、UDP、部分内置请求会绕过）。
- **进程规则**：流量到达 TUN 时，sing-box 识别发起连接的**进程名/路径**，按规则命中后路由到指定出站。

---

## 3. 操作步骤

1. **以管理员身份运行**代理软件（右键 → "以管理员身份运行"）。
   - 无管理员权限时 TUN 会报错：`Initialize TUN device failed` / `permission denied`。
   - 无管理员权限只能退回"系统代理模式"，但**系统代理模式没有进程级分流能力**。
2. **开启 TUN 模式**（确认 `auto_route: true`）。
3. **准备两个出站**：一个代理节点（如"节点选择"），一个 `DIRECT` 直连。
4. **编辑 sing-box 的 config.json**（GUI 类工具一般在"配置/规则"界面操作，或直接改它生成的配置文件）。
5. **在 `route.rules` 数组的最前面**插入进程规则（见第 4 节）。
6. **保存配置并重启核心**（或重启代理软件）。
7. **验证**（见第 6 节）。

---

## 4. 配置示例

### 4.1 在现有配置中插入（推荐，改动最小）

在现有 `route.rules` 数组的**第一项之前**插入两条规则：

```json
"rules": [
  {
    "process_name": ["chrome.exe", "msedge.exe"],
    "action": "route",
    "outbound": "节点选择"
  },
  {
    "process_name": ["WeChat.exe", "QQ.exe"],
    "action": "route",
    "outbound": "全球直连"
  },
  {
    "rule_set": ["GeoSite-Private"],
    "action": "route",
    "outbound": "全球直连"
  }
]
```

> ⚠️ **规则顺序关键**：进程规则必须放在 `GeoLocation-!CN → 节点选择` 那条**之前**，否则 B 软件访问非中国资源仍会被后面的规则分流到代理。

### 4.2 完整可运行的最小配置

```json
{
  "log": { "level": "debug" },
  "dns": {
    "servers": [
      { "tag": "local", "address": "223.5.5.5" },
      { "tag": "remote", "address": "https://8.8.8.8/dns-query", "detour": "PROXY" }
    ]
  },
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "address": ["172.19.0.1/30"],
      "auto_route": true,
      "strict_route": true,
      "stack": "mixed"
    }
  ],
  "outbounds": [
    { "type": "direct", "tag": "DIRECT" },
    { "type": "selector", "tag": "PROXY", "outbounds": ["香港-03", "香港-04"] }
  ],
  "route": {
    "rules": [
      { "process_name": ["chrome.exe", "msedge.exe"], "action": "route", "outbound": "PROXY" },
      { "process_name": ["WeChat.exe", "QQ.exe"], "action": "route", "outbound": "DIRECT" },
      { "ip_is_private": true, "action": "route", "outbound": "DIRECT" }
    ],
    "final": "PROXY",
    "auto_detect_interface": true
  }
}
```

- `PROXY` 里的节点换成你自己的节点。
- 进程名匹配不到时改用 `process_path` 写完整路径：

```json
{ "process_path": ["C:\\Program Files\\Tencent\\WeChat\\WeChat.exe"], "action": "route", "outbound": "DIRECT" }
```

---

## 5. 进程名怎么找

任务管理器 → 切到 **"详细信息"** 标签，查看目标软件的进程名（**必须带 `.exe`**）。

| 软件 | 进程名 |
|---|---|
| Chrome | `chrome.exe` |
| Edge | `msedge.exe` |
| 微信 | `WeChat.exe` |
| QQ | `QQ.exe` |
| 钉钉 | `DingTalk.exe` |

命令行确认：

```powershell
tasklist | findstr 微信
```

---

## 6. 验证方法

1. **A 软件**（如 Chrome）打开 `https://ipinfo.io/ip` → 显示**海外 IP** ✅
2. **B 软件**（如 微信）打开 `https://ipinfo.io/ip` → 显示**国内 IP** ✅
3. **看日志**：把 `log.level` 设为 `debug`，日志中会显示 `process_name` 命中与出站选择，最直观。

---

## 7. 常见坑

| 坑 | 说明 | 对策 |
|---|---|---|
| 进程名没带 `.exe` | 匹配不中 | 补上 `.exe` |
| 规则顺序错 | 进程规则被后面的 GeoLocation-!CN 抢走 | 进程规则放 `route.rules` 最前 |
| 部分软件匹配不到 | 该软件的网络请求实际由系统进程（如 `svchost.exe`）发出 | 改用 `process_path`，或该软件自带代理设置时走"双端口方案" |
| 杀软拦截 | 虚拟网卡驱动装不上 | 将 sing-box 加入杀毒软件白名单 |
| 无管理员权限 | TUN 启动失败 | 无权限环境只能退回系统代理模式，无进程分流能力 |
| TUN 适配器冲突 | 旧版 Clash / v2rayN / Hiddify 残留虚拟网卡 | 设备管理器清理多余网络适配器后重试 |
| stack 兼容问题 | 个别软件异常 | 将 `tun.stack` 从 `system` 改为 `gvisor` |

---

## 8. 备选方案：双端口方案

当 TUN + 进程规则存在兼容问题，或软件不支持被 TUN 识别时：

- 保留代理软件开一个**本地 SOCKS/HTTP 端口**（如 `127.0.0.1:18888`）。
- **A 软件**在自身设置里填入该端口走代理。
- **B 软件**不填，即直连。

| 优点 | 缺点 |
|---|---|
| 无需 TUN、无需管理员权限 | 要求软件支持自定义代理 |
| 精确到单软件 | git、curl、浏览器插件等需逐个配置 |

示例（git）：

```bash
# A 软件走代理
git config --global http.proxy http://127.0.0.1:18888
git config --global https.proxy http://127.0.0.1:18888
```

---

## 9. 参考文档

- [sing-box 文档 — Route Rule（process_name / process_path：Linux / Windows / macOS）](https://sing-box.sagernet.org/configuration/route/rule/)
- [sing-box 文档 — TUN Inbound](https://sing-box.sagernet.org/configuration/inbound/tun/)
- [sing-box 文档 — Headless Rule](https://sing-box.sagernet.org/configuration/rule-set/headless-rule/)
- [GUI.for.SingBox 用户指南 — 进程/源端口规则与管理员权限](https://gui-for-cores.github.io/zh/guide/gfs/community)
