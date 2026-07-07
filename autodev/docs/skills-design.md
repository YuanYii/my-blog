# 自动化开发工作流 Skills 设计方案

> 2026-07-07 起草，基于 `autodev/promopt/LLM-*.md` 8 个提示词转换
> 整合为 1 个 `autodev-flow` Skill（8 个阶段：Stage 0-7）

---

## 1. 概述

### 1.1 是什么

一套基于 Codex Skills 的**自动化开发工作流**，将软件开发生命周期（SDLC）拆分为 8 个阶段，整合封装为 1 个 `autodev-flow` Skill，支持完整工作流执行或单阶段触发。

### 1.2 核心价值

| 价值 | 说明 |
|---|---|
| **标准化** | 每个阶段有明确的输入/输出/检查清单，消除人工随意性 |
| **可复用** | 参数化配置，一套 Skill 适配多个项目 |
| **可追溯** | 每轮执行产出报告 + 状态文件，完整审计链 |
| **可扩展** | 新增阶段只需添加 reference 文件，不影响现有工作流 |

### 1.3 适用场景

- 个人项目的日常迭代
- 小团队的自动化开发流程
- 需要质量保证的代码交付

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
├── auto_iteration/                # 任务跟踪文档
│   └── {YYYYMMDD}.md             # 每日任务卡
├── auto_audit/                    # 审计报告
│   └── {YYYYMMDD}/
│       ├── stage3.md             # 代码审查报告
│       ├── stage4.md             # 测试审计报告
│       ├── stage5.md             # 集成测试报告
│       └── stage6.md             # 门控文件
└── skills/
    └── autodev-flow/              # 唯一 Skill
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

---

## 3. Skill 定义

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

### 4.1 首次使用

```bash
# 1. 复制 skill 到项目
cp -r /path/to/autodev-flow /your/project/autodev/skills/

# 2. 编辑 config.json 配置项目信息
vim /your/project/autodev/skills/autodev-flow/config.json

# 3. 使用 skill
"使用 autodev-flow skill，需求：新增文章收藏功能"
```

### 4.2 日常使用

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
```

---

## 6. 文件命名规范

### 6.1 Skill 目录命名

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
