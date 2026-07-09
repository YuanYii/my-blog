# 自动化开发工作流 设计方案

> 2026-07-07 起草，基于 `autodev/promopt/LLM-*.md` 8 个提示词转换
> 整合为 1 套 `autodev-flow` 工作流指令（8 个阶段：Stage 0-7），支持多种 AI Agent

---

## 1. 概述

### 1.1 是什么

一套**通用的自动化开发工作流**，将软件开发生命周期（SDLC）拆分为 8 个阶段，以标准化 Markdown 指令文件交付，可被 Codex、Claude Code、Cursor 等多种 AI Agent 加载执行。支持完整工作流执行或单阶段触发。

### 1.2 核心价值

| 价值 | 说明 |
|---|---|
| **标准化** | 每个阶段有明确的输入/输出/检查清单，消除人工随意性 |
| **可复用** | 参数化配置，一套工作流适配多个项目 |
| **可追溯** | 每轮执行产出报告 + 状态文件，完整审计链 |
| **可扩展** | 新增阶段只需添加 reference 文件，不影响现有工作流 |
| **通用兼容** | 纯 Markdown 指令，不依赖特定 Agent 的私有协议或 SDK |

### 1.2.1 预估性能指标

> 以下为基于工作流设计的预估值，实际数据待跑通后补充。

| 指标 | 预估 | 说明 |
|------|------|------|
| 对话轮次 | 降低 ~40% | 结构化阶段输入/输出，减少需求澄清和上下文重复加载 |
| Token 消耗 | 减少 ~30% | Reference 文件按需加载，每阶段只读取相关指令 |
| 首次通过率 | ≥ 80% | 任务卡含验证清单 + 代码审查自动拦截 |
| 端到端周期 | 缩短 ~50% | 审查/测试/文档同步自动串联，无需人工等待 |
| 遗留缺陷 | ≤ 10% | 门控复核 + 跨天挂起提醒，未修复项不会静默流失 |

### 1.3 适用场景

| 场景 | 说明 |
|------|------|
| **个人项目迭代** | 日常需求开发，自动走完审查-测试-文档全链路 |
| **小团队协作** | 统一开发流程，减少人工遗漏 |
| **质量保证交付** | 强制代码审查 + 测试审计 + 门控复核 |
| **多 Agent 环境** | 团队成员使用不同 Agent（有人用 Codex，有人用 Claude），工作流保持一致 |
| **CI/CD 集成** | 各阶段产出物（报告、状态文件）可被 CI 管道读取 |

### 1.4 兼容 Agent

| Agent | 加载方式 | 说明 |
|-------|---------|------|
| **Codex** | `.codex/skills/autodev-flow/SKILL.md` | 支持 skill-installer 一键安装 |
| **Claude Code** | `.claude/skills/autodev-flow/SKILL.md` | 原生 skills 目录，自动发现 |
| **Cursor** | `.cursorrules` 或 `.cursor/rules/` | 需将 SKILL.md 内容整合到 rules 文件 |
| **通用** | 任何支持 Markdown 指令的 Agent | 将 `autodev-flow/` 目录置于 Agent 可访问路径即可 |

> **设计原则**：工作流逻辑全部用标准 Markdown 编写，不依赖任何 Agent 的私有 API、SDK 或插件协议。Agent 的差异仅体现在"如何加载 SKILL.md"，工作流执行逻辑完全一致。

---

## 2. 架构设计

### 2.1 工作流架构

```
用户需求
    ↓
┌─────────────────────────────────────────────────────────┐
│                    工作流（Workflow）                      │
│                                                         │
│  Stage 0    Stage 1    Stage 2    Stage 3    Stage 4    │
│  ┌─────┐   ┌─────┐   ┌─────┐   ┌─────┐   ┌─────┐     │
│  │项目  │→→→│需求  │→→→│开发  │→→→│代码  │→→→│测试  │     │
│  │检测  │   │拟定  │   │实现  │   │审查  │   │审计  │     │
│  └─────┘   └─────┘   └─────┘   └─────┘   └─────┘     │
│                                                         │
│  Stage 5    Stage 6    Stage 7                          │
│  ┌─────┐   ┌─────┐   ┌─────┐                          │
│  │集成  │→→→│门控  │→→→│文档  │                          │
│  │测试  │   │复核  │   │同步  │                          │
│  └─────┘   └─────┘   └─────┘                          │
│                                                         │
└─────────────────────────────────────────────────────────┘
    ↓
交付物（代码 + 文档）
```

### 2.2 数据流

```
autodev/
├── config.json                    # 项目配置（全局共享）
├── status.json                    # 工作流状态（全局共享）
├── status.sh                      # 状态查看脚本（Stage 0 自动拷贝）
├── auto_iteration/                # 任务跟踪文档
│   └── {YYYYMMDD}.md             # 每日任务卡
├── auto_audit/                    # 审计报告
│   └── {YYYYMMDD}/
│       ├── {YYYYMMDD}-stage3.md  # 代码审查报告
│       ├── {YYYYMMDD}-stage4.md  # 测试审计报告
│       ├── {YYYYMMDD}-stage5.md  # 集成测试报告
│       └── {YYYYMMDD}-stage6.md  # 门控文件
└── skills/
    └── autodev-flow/              # 工作流指令（通用格式）
        ├── SKILL.md              # 主入口（工作流说明）
        ├── config.json           # 项目配置
        └── references/           # 各阶段详细指令
            ├── LLM-00-project-detect.md
            ├── LLM-01-requirement-drafter.md
            ├── LLM-02-developer.md
            ├── LLM-03-code-reviewer.md
            ├── LLM-04-test-engineer.md
            ├── LLM-05-integration-tester.md
            ├── LLM-06-project-manager.md
            └── LLM-07-doc-engineer.md
```

> `skills/autodev-flow/` 是通用指令目录。各 Agent 的安装只是将此目录链接/复制到自己的加载路径（如 `.codex/skills/`、`.claude/skills/`），指令内容完全一致。

---

## 3. 工作流定义

### 3.1 autodev-flow（自动化开发工作流）

**职责**：完整的 SDLC 自动化工作流，支持 8 个阶段

**触发条件**：用户说"执行工作流" / "执行开发流程" / 指定阶段执行

**输入**：用户需求或任务文件

**输出**：代码改动 + 文档更新

**核心能力**：
- Stage 0：项目检测（生成 config.json）
- Stage 1：需求拟定（生成任务卡）
- Stage 2：开发实现（处理任务）
- Stage 3：代码审查（质量报告）
- Stage 4：测试审计（验收报告）
- Stage 5：集成测试（运行验证）
- Stage 6：门控复核（生成修复卡）
- Stage 7：文档同步（增量更新）

**结构**：
```
autodev-flow/
├── SKILL.md                      # 主入口
├── config.json                   # 项目配置
└── references/                   # 各阶段指令
    ├── LLM-00-project-detect.md
    ├── LLM-01-requirement-drafter.md
    ├── LLM-02-developer.md
    ├── LLM-03-code-reviewer.md
    ├── LLM-04-test-engineer.md
    ├── LLM-05-integration-tester.md
    ├── LLM-06-project-manager.md
    └── LLM-07-doc-engineer.md
```

---

## 4. 使用方式

### 4.1 安装

将 `autodev-flow/` 目录放置到你的 Agent 可访问的路径即可：

| Agent | 目标路径 | 命令 |
|-------|---------|------|
| Codex | `.codex/skills/autodev-flow/` | `npx @openai/codex skill install --repo 用户名/autodev-flow` |
| Claude Code | `.claude/skills/autodev-flow/` | `cp -r autodev-flow ~/.claude/skills/autodev-flow` |
| Cursor | `.cursor/rules/` | 将 SKILL.md 内容整合到 `.cursorrules` |
| 通用 | `autodev/skills/autodev-flow/` | `cp -r autodev-flow /your/project/autodev/skills/` |

安装后编辑 `config.json`，填入项目实际信息。

### 4.2 日常使用

以下示例适用于所有 Agent，在对话中直接输入即可：

```bash
# 方式 A：执行完整工作流
"使用 autodev-flow 执行完整工作流"

# 方式 B：执行单个阶段
"使用 autodev-flow，执行 Stage 2 开发"

# 方式 C：执行特定任务
"使用 autodev-flow，处理 20260707-DEV-001"
```

### 4.3 查看状态

```bash
# 查看工作流状态
cat autodev/status.json

# 查看当天任务
cat autodev/auto_iteration/$(date +%Y%m%d).md

# 查看审计报告
ls auto_audit/$(date +%Y%m%d)/
```

---

## 5. 配置文件

### 5.1 config.json 结构

```json
{
  "_comment": "autodev-flow 项目配置文件",

  "project": {
    "_comment": "项目基本信息",
    "name": "项目名（Maven artifactId / package.json name）",
    "nameEn": "英文项目名",
    "workspace": "项目根目录描述"
  },

  "modules": {
    "_comment": "项目模块结构（用于编译检查、文件定位）",
    "backend": "后端目录名",
    "frontend": "前端目录名",
    "app": "应用模块名"
  },

  "database": {
    "_comment": "数据库配置",
    "type": "数据库类型（SQLite / MySQL / PostgreSQL）",
    "devFile": "开发数据库文件名",
    "schemaSqlite": "SQLite schema 路径",
    "schemaMysql": "MySQL schema 路径"
  },

  "techStack": {
    "_comment": "技术栈（用于代码审查规则、编译命令）",
    "backend": "后端框架（Spring Boot / Express / Django）",
    "orm": "ORM 框架（MyBatis-Plus / Sequelize / Prisma）",
    "frontend": "前端框架（Nuxt 3 / Next.js / Vue）",
    "language": ["编程语言列表"],
    "cache": "缓存技术（Redis / Memcached）"
  },

  "ports": {
    "_comment": "服务端口（用于启动检查、API 调用）",
    "backend": "后端端口号",
    "frontend": "前端端口号",
    "redis": "Redis 端口号"
  },

  "api": {
    "_comment": "API 配置（用于健康检查、接口调用）",
    "prefix": "API 前缀（如 /api/v1）",
    "healthCheck": "健康检查端点"
  },

  "docs": {
    "_comment": "文档路径（用于文档同步）",
    "design": "设计文档路径",
    "readme": "README 路径",
    "agents": "AGENTS.md 路径"
  },

  "docker": {
    "_comment": "Docker 配置（用于集成测试启动服务）",
    "redisImage": "Redis Docker 镜像",
    "containerName": "Redis 容器名称"
  }
}
```

### 5.2 status.json 结构

```json
{
  "stages": {
    "Stage_0": {
      "status": "success",
      "last_update": "2026-07-07 03:00:00",
      "message": "已检测项目架构，生成 config.json"
    },
    "Stage_1": {
      "status": "success",
      "last_update": "2026-07-07 03:15:00",
      "message": "已完成需求拟定，生成 2 个任务"
    }
  }
}
```

---

## 6. 文件命名规范

### 6.1 工作流目录命名

- 使用 hyphen-case（小写字母 + 连字符）
- 示例：`autodev-flow`

### 6.2 Reference 文件命名

- 格式：`LLM-{NN}-{name}.md`
- 示例：`LLM-00-project-detect.md`、`LLM-01-requirement-drafter.md`

### 6.3 任务文档命名

- 格式：`{YYYYMMDD}.md`（如 `20260707.md`）
- 任务编号：`{YYYYMMDD}-{TYPE}-{NNN}`（如 `20260707-DEV-001`）

### 6.4 审计报告命名

- 格式：`{YYYYMMDD}-stage{N}.md`（如 `20260707-stage3.md`）

---

## 7. 扩展指南

### 7.1 新增阶段

1. 在 `references/` 目录下创建 `LLM-{NN}-{name}.md`
2. 在 `SKILL.md` 中添加阶段说明
3. 更新 `config.json`（如需要）

### 7.2 自定义检查清单

在 `references/` 目录下创建检查清单文件，阶段执行时读取。

### 7.3 集成外部工具

在阶段指令中添加脚本调用命令。

---

## 8. 注意事项

### 8.1 安全

- 不要在 config.json 中存储敏感信息（密码、token）
- 敏感操作（如发布）需要用户确认

### 8.2 性能

- 单次最多处理 5 个任务（避免超时）
- 大文件审查只检查关键方法

### 8.3 回滚

- 开发工程师编译失败 → 立即回滚
- 测试不通过 → 标记为 🔴 未完成
