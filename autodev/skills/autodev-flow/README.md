# AutoDev Flow

> **通用的自动化开发工作流**——把 SDLC 拆成 8 个阶段，纯 Markdown 指令交付，可被 Codex、Claude Code、Cursor 等任意 AI Agent 加载执行，自带闭环迭代与门控审计。

## 核心优势

- **通用兼容** — 纯 Markdown 指令，Codex / Claude / Cursor 执行逻辑完全一致，团队各用各的 Agent 也能流程统一。
- **8 阶段全链路** — 项目检测 → 需求 → 开发 → 审查 → 审计 → 测试 → 门控 → 文档，前序产出即后序输入，缺一不可。
- **闭环自动迭代** — Stage 6 把异常项自动回种为任务卡（`autopush-*`），下一轮 Stage 2 接手处理；不修复不放行。
- **三层质量把关** — 静态审查（规范/性能/安全 17+ 检查项）→ 静态审计（四态判定）→ 运行验证（4 类验证），门控复核统一收口。
- **完整审计链** — 任务卡 + 审计报告 + status.json 三类产物，Stage 7 渲染状态表到追踪文档，一份文档看完全部 Stage。
- **职责分离** — 开发不审查、审查不修复、审计不写代码，角色边界清晰，产出可独立审计。
- **防覆盖保护** — 任务文件只追加不覆盖、续号前先读、幂等写入、元信息照抄。
- **工程化** — 参数化配置适配多项目、单阶段触发、任务去重、跨天挂起提醒、服务自动启动、CI/CD 友好。

## 性能优势

> 以下为基于工作流设计的预估值，实际数据待跑通后补充。

| 指标 | 预估 | 说明 |
|------|------|------|
| 对话轮次 | **降低 ~40%** | 结构化阶段输入/输出，减少需求澄清和上下文重复加载的来回 |
| Token 消耗 | **减少 ~30%** | Reference 文件按需加载，每阶段只读取相关指令，避免全量 prompt |
| 首次通过率 | **≥ 80%** | 任务卡含验证清单 + 代码审查自动拦截，需求理解偏差在 Stage 4 前暴露 |
| 端到端周期 | **缩短 ~50%** | 审查/测试/文档同步自动串联，无需人工等待和手动衔接 |
| 遗留缺陷 | **≤ 10%** | 门控复核 + 跨天挂起提醒，未修复项不会静默流失 |

## 快速开始

**安装**：

```bash
# Codex
npx @openai/codex skill install --repo 你的用户名/autodev-flow
# Claude Code
cp -r autodev-flow ~/.claude/skills/autodev-flow
```

**直接拷贝到项目路径**（不依赖 Agent 的 skills 目录，任何 Agent 通用）：

```bash
# 1. 将 autodev-flow 目录拷贝到项目下的 autodev/skills/
mkdir -p autodev/skills
cp -r autodev-flow autodev/skills/autodev-flow

# 2. 编辑项目配置（或让 Stage 0 自动生成）
vim autodev/skills/autodev-flow/config.json

# 3. 在对话中告诉 Agent 工作流指令的位置即可触发
#    "读取 autodev/skills/autodev-flow/SKILL.md，执行完整工作流"
```

> 这种方式把工作流指令随项目一起版本管理，团队成员 clone 仓库后即可使用，无需各自安装。

**使用**（所有 Agent 通用，对话中直接输入）：

```bash
"使用 autodev-flow，检测项目架构"          # 首次：生成 config.json
"使用 autodev-flow，执行 Stage 1，帮我开发一个贪吃蛇游戏，要求使用html+js" # 需求分析
"使用 autodev-flow，执行 Stage 1，需求1:....;需求2:....;需求3:...." # 多轮需求分析
"使用 autodev-flow，执行 Stage 1，更新需求 20260601-DEV-001：.......。" # 需求更新
"使用 autodev-flow，执行 Stage 2-7"      # 从开发到文档同步（Stage 0/1 已完成时）
```

**查看状态**：

```bash
# 统一命令（Stage 0 自动拷贝到 autodev/status.sh）
bash autodev/status.sh                    # 阶段状态面板（需 jq）
bash autodev/status.sh --tasks            # 展示任务状态
bash autodev/status.sh --watch            # 动态刷新（默认 10 秒）
bash autodev/status.sh --watch 5          # 自定义刷新间隔
bash autodev/status.sh --watch --tasks    # 刷新 + 任务状态

# 方式 2：原始 JSON
cat autodev/status.json

# 方式 3：追踪文档（含 Stage 7 渲染的状态表）
cat autodev/auto_iteration/$(date +%Y%m%d).md

# 方式 4：审计报告
ls autodev/auto_audit/$(date +%Y%m%d)/
```

## 配置说明

**优先使用 Stage 0 自动检测**：首次执行时运行 `"使用 autodev-flow，检测项目架构"`，Stage 0 会扫描项目目录自动生成 `config.json`。仅当自动检测结果不准确时，再手动补充以下字段：

| 分组 | 字段 | 说明 | 示例 |
|------|------|------|------|
| project | name | 项目名 | `my-blog` |
| project | nameEn | 英文项目名 | `my-blog` |
| project | workspace | 项目根目录 | `/Users/dev/my-blog` |
| modules | backend | 后端目录名 | `backend` |
| modules | frontend | 前端目录名 | `frontend` |
| modules | app | 应用模块名 | `app` |
| database | type | 数据库类型 | `SQLite` / `MySQL` / `PostgreSQL` |
| database | devFile | 开发数据库文件 | `dev.db` |
| database | schemaSqlite | SQLite schema 路径 | `docs/schema-sqlite.sql` |
| database | schemaMysql | MySQL schema 路径 | `docs/schema-mysql.sql` |
| techStack | backend | 后端框架 | `Spring Boot` / `Express` / `Django` |
| techStack | orm | ORM 框架 | `MyBatis-Plus` / `Prisma` |
| techStack | frontend | 前端框架 | `Nuxt 3` / `Next.js` / `Vue` |
| techStack | language | 编程语言 | `["Java", "TypeScript"]` |
| techStack | cache | 缓存技术 | `Redis` |
| ports | backend | 后端端口 | `8080` |
| ports | frontend | 前端端口 | `3000` |
| ports | redis | Redis 端口 | `6379` |
| api | prefix | API 前缀 | `/api/v1` |
| api | healthCheck | 健康检查端点 | `/health` |
| docs | design | 设计文档路径 | `docs/design/设计文档.md` |
| docs | readme | README 路径 | `README.md` |
| docs | agents | AGENTS.md 路径 | `AGENTS.md` |
| docker | redisImage | Redis 镜像 | `redis:7-alpine` |
| docker | containerName | Redis 容器名 | `my-blog-redis` |

## 工作流概览

```
Stage 0 项目检测 → config.json
   ↓
Stage 1 需求拟定 → 任务卡（BUG/OPT/DEV，含验证清单）
   ↓
Stage 2 开发实现 → 代码改动 + 处理报告（编译验证）
   ↓
Stage 3 代码审查 → 审查报告（🔴 严重问题回种 BUG 区段）
   ↓
Stage 4 测试审计 → 验收报告（✅/⚠️/❌/🔍 四态判定）
   ↓
Stage 5 集成测试 → 测试报告（🔍 项转为 ✅/❌ 确定结论）
   ↓
Stage 6 门控复核 → 修复卡（异常项回种）+ 门控文件
   ↓
Stage 7 文档同步 → README + 设计文档增量更新 + 工作流状态表
```

| Stage | 角色 | 核心动作 | 指令文件 |
|-------|------|----------|----------|
| 0 | 项目检测 | 扫描技术栈/端口/模块，生成 config.json | `LLM-00-project-detect.md` |
| 1 | 需求拟定 | 拆解+去重+编号，生成结构化任务卡 | `LLM-01-requirement-drafter.md` |
| 2 | 开发实现 | BUG→OPT→DEV 顺序处理，编译必过 | `LLM-02-developer.md` |
| 3 | 代码审查 | 规范/性能/安全三维度，🔴 回种 | `LLM-03-code-reviewer.md` |
| 4 | 测试审计 | 静态核对验证清单，四态判定 | `LLM-04-test-engineer.md` |
| 5 | 集成测试 | 实际运行验证 🔍 项，服务自动启动 | `LLM-05-integration-tester.md` |
| 6 | 门控复核 | 异常项分类映射为 autopush 卡片 | `LLM-06-project-manager.md` |
| 7 | 文档同步 | 门控检查 + 增量同步 + 状态渲染 | `LLM-07-doc-engineer.md` |

## 工作流产出说明

每个 Stage 执行后都会产出明确的文件，形成完整审计链：

| Stage | 产出文件 | 作用 |
|-------|----------|------|
| 0 | `autodev/config.json` | 项目配置（技术栈/端口/模块路径），后续 Stage 共享 |
| 1 | `autodev/auto_iteration/{RUN}.md`（追加任务卡） | 结构化任务卡：编号、描述、验证清单、涉及文件 |
| 2 | 代码改动 + 处理报告（追加到任务文档） | 实际代码修改 + 每个任务的处理结果与验证结论 |
| 3 | `autodev/auto_audit/{RUN}/{RUN}-stage3.md` | 代码审查报告：🔴严重/🟡警告/🟢建议，含 文件:行号 |
| 4 | `autodev/auto_audit/{RUN}/{RUN}-stage4.md` | 需求验收报告：每条验证清单的 ✅/⚠️/❌/🔍 判定 |
| 5 | `autodev/auto_audit/{RUN}/{RUN}-stage5.md` | 集成测试报告：🔍 项的实际运行结论（✅/❌）+ 证据 |
| 6 | `autodev/auto_audit/{RUN}/{RUN}-stage6.md` + autopush 卡片 | 门控文件（异常计数）+ 异常项回种到任务文档 |
| 7 | README + 设计文档（增量更新）+ 状态表 | 文档同步 + `status.json` 渲染到任务文档末尾 |

三类核心产物：

| 产物 | 路径 | 说明 |
|------|------|------|
| **任务追踪文档** | `autodev/auto_iteration/{RUN}.md` | 当天全貌：任务卡 + 处理报告 + 工作流状态表，一份文档看完所有 Stage 结论 |
| **审计报告** | `autodev/auto_audit/{RUN}/{RUN}-stage{3-6}.md` | Stage 3/4/5/6 各自独立报告，互不覆盖，可单独回溯 |
| **状态文件** | `autodev/status.json` | 8 个 Stage 实时状态（success/failed/skipped），流水线唯一真相源 |

> **设计要点**：所有产出均为 Markdown / JSON 纯文本，可被 Git 版本管理、可被 CI 管道读取、可被人工直接阅读。任务文件只追加不覆盖，历史记录完整保留。

## 数据流

```
autodev/
├── config.json              # 项目配置（Stage 0 生成）
├── status.json              # 工作流状态（唯一真相源）
├── status.sh                # 状态查看脚本（Stage 0 自动拷贝）
├── auto_iteration/{YYYYMMDD}.md   # 任务卡 + 处理报告 + 状态表
├── auto_audit/{YYYYMMDD}/
│   ├── {YYYYMMDD}-stage3.md  # 代码审查
│   ├── {YYYYMMDD}-stage4.md  # 测试审计
│   ├── {YYYYMMDD}-stage5.md  # 集成测试
│   └── {YYYYMMDD}-stage6.md  # 门控文件
└── skills/autodev-flow/     # 本工作流指令（SKILL.md + config.json + references/）
```

## 关键规则

| 规则 | 说明 |
|------|------|
| 防覆盖 | 任务文件只能 Edit 追加，严禁 Write 覆盖 |
| 任务编号 | `{RUN}-{TYPE}-{NNN}`；回种卡 `autopush-{RUN}-{TYPE}-{NNN}` |
| 单次上限 | 最多 5 个任务；跨天挂起任务单列提醒 |
| 门控检查 | Stage 7 仅在 Stage 6 的 BUG=0 且 DEV=0 时执行 |
| 状态真相源 | `status.json` 是流水线状态唯一来源 |
| 时区 | 所有时间字段用北京时区 UTC+8 |

## 兼容 Agent

| Agent | 目录 |
|-------|------|
| Codex | `.codex/skills/autodev-flow/` |
| Claude Code | `.claude/skills/autodev-flow/` |
| Cursor | `.cursor/rules/autodev-flow.md` |
| 通用 | 任意 Agent 可访问路径 |

## License

MIT
