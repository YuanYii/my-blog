# AutoDev Flow

> 契约驱动的 Graph 工作流引擎 —— 把 AI 编码从"聊天"升级为"工程"。

让 AI Agent 按软件工程的最佳实践来写代码：一个由 8 个节点组成的有向工作流图。节点之间通过条件边（PASSED / REJECTED / BLOCKED）自动分支、回环、熔断。每个节点产出强类型 YAML 契约，协议化传递而非口口相传。

---

> **工作流节点**：Stage 0 项目检测 → Stage 1 需求拟定 → Stage 2 代码实现 → Stage 3 代码审查 → Stage 4 静态审计 → Stage 5 集成测试 → Stage 6 门控复核 → Stage 7 文档同步
> 
> *注：Stage 0 为前置初始化阶段；Stage 1~7 配合 `bug_fix` 修复分支共同组成 Graph 引擎的 8 个运行时节点。*

## 快速使用

### 前置要求

- 任一 AI Agent：Codex / Claude Code / Cursor / 或其他能读取 Markdown 指令的 Agent

### 安装

**Git clone 到项目内（使用多个agent时可不用多次安装）**

```bash
cd /path/to/your-project

# 克隆工作流到项目 skills 目录，并删除 .git（不需要版本历史）
git clone --depth 1 https://github.com/YuanYii/autodev-flow.git autodev/skills/autodev-flow && rm -rf autodev/skills/autodev-flow/.git
```

### 项目初始化

在项目根目录下启动agent，发送：

​	**使用 autodev-flow，检测项目架构**

```
→ Agent 自动扫描项目目录、识别技术栈
→ 生成 autodev/config.json
→ 自动初始化工作流图 + 拷贝状态脚本
```

> 💡 Stage 0 会自动用 AI 填充 `template/config.template.json` 中的 `{{占位符}}`，生成 `autodev/config.json`。同时将 `status.sh` / `status.ps1` 从 skill 目录拷贝到 `autodev/` 根目录。仅在自动检测不准确时，才需要手动修正 config.json 中的个别字段。

### 执行任务

与agent对话：

​	**使用 autodev-flow，执行 Stage 1，需求：新增用户注册功能**

```
→ Agent 和你进行 1-3 轮沟通 → 产出结构化任务卡
```

​	**使用 autodev-flow，执行 Stage 2-6**

```
→ 代码实现 → 审查 → 审计 → 测试 → 门控
```

### 查看状态

查看实时状态（在项目根目录执行，即包含 `autodev/` 工作区的目录）：

```bash
# macOS / Linux
bash autodev/status.sh --watch      # 动态刷新（默认 10 秒）

# Windows PowerShell
powershell -ExecutionPolicy Bypass -File autodev\status.ps1 -Watch
# ╔══════════════════════════════════════════════╗
# ║    AutoDev Flow · 20260804                   ║
# ╚══════════════════════════════════════════════╝
#   ✅ Stage 1 需求拟定 │ success
#   ✅ Stage 2 代码实现 │ success
#   ✅ Stage 3 代码审查 │ success
#   🟠 Stage 4 静态审计 │ running
#   ⏸️ Stage 5 集成测试 │ pending
#   ⏸️ Stage 6 门控     │ pending
#   ⏸️ Stage 7 文档同步 │ pending
```

---

## 为什么需要一套工作流？

用 AI 写代码的典型痛点：

- **需求靠脑补**：口头描述几句就让 AI 开工，边界没聊清楚就生成一堆代码
- **边写边审**：同一个 AI 写完代码马上审查自己，质量全靠运气
- **缺陷漏了没人追**：发现问题后靠人记着去修，一忙就忘了
- **换 Agent 就断档**：团队里有人用 Codex 有人用 Cursor，流程各玩各的
- **不知道做到哪了**：聊天记录翻半天才找到上次改了什么

autodev-flow 把 SDLC 的工程纪律搬进 AI 对话里 —— 不是限制 AI，是让 AI 干得更靠谱。

---

## 与直接对话 AI 写代码有什么区别？

| 维度 | 直接对话 AI 写代码 | autodev-flow |
|------|------------------------|-------------|
| **需求管理** | 口头描述，AI 凭记忆理解 | Stage 1 强制 1-3 轮沟通澄清 → 结构化任务卡（含验证清单） |
| **职责分离** | 同一个 AI 边写边审查 | 开发不审查、审查不修复、审计不写代码 |
| **质量保障** | 靠 AI 自觉检查 | 三层把关：静态审查（17+ 检查项）→ 静态审计（四态判定）→ 运行验证 |
| **缺陷闭环** | 发现后靠人追 | Stage 6 自动回种任务卡，下一轮 Stage 2 接手修复 |
| **审计追溯** | 聊天记录里翻 | 每阶段产出独立 Markdown / 契约文件，Git 可追踪 |
| **多 Agent 兼容** | 绑死一个工具 | 纯 Markdown 指令，同一套流程在 Codex / Claude / Cursor 上输出一致 |
| **重复执行** | 每次从零解释项目背景 | 一次配置 config.json，后续跑多少次都不变 |

---

## 核心理念：Graph 驱动，而非线性流水线

传统 CI/CD 是固定顺序的线性流水线。autodev-flow 不同——它是一个**有向图**（Directed Graph）。

- **节点 = 独立 Agent 角色**：每个节点定义了一个 Agent 角色（PM / DEV / REVIEWER / TESTER），加载专属的阶段指令文件，互不越界。
- **边 = 条件路由**：节点之间不只有"下一步"——审查通过走审计路径，审查打回走修复回环，审查阻塞走降级兜底。每一次流转由前一个节点的 YAML 契约中的字段值决定。
- **契约 = 强类型传递**：阶段间不依赖 AI 的"记忆"。每个节点完成后必须输出结构化 YAML（overall_result、metrics、issues），下一节点以此为准入条件。契约缺失则流程阻断，不跳步、不推测。
- **熔断 = 安全阀**：审查—修复回环最多 3 轮。第 4 次 REJECTED 时自动触发 BLOCKED，防止死循环浪费 Token。可通过 reset 命令恢复。

默认工作流图的拓扑（Graph 语法声明在 `template/workflows/*.workflow.yaml`）：

```
                     ┌──────────┐
                     │ requirement│ ← Stage 1: 需求分析
                     └─────┬────┘
                           │
                     ┌─────▼────┐
                     │  develop  │ ← Stage 2: 代码实现
                     └─────┬────┘
                           │
                     ┌─────▼────┐   REJECTED (≤3次)
                     │  review   │──────────────────┐
                     └─┬───┬───┬┘                  │
            PASSED     │   │   │  BLOCK            │
          ┌────────────┘   │   └──────────┐        │
          │           is_default          │        │
          │                └──────┐       │        │
     ┌────▼────┐                 │       │   ┌────▼────┐
     │test_audit│◄────────────────┘       │   │ bug_fix │ ← 修复回环
     └────┬────┘                         │   └────┬────┘
          │                              │        │
     ┌────▼────┐                         │
     │integration_test│                  │
     └────┬────┘                         │    合约 → 回到review)
          │                              │
     ┌────▼────┐                         │
     │gate_check│◄───────────────────────┘
     └──┬───┬──┘
 PASSED │   │ BLOCKED
   ┌────▼┐  └──→ null（暂停）
   │ doc │ ← Stage 7: 文档同步
   └─────┘
```

| 边 | 条件 | 含义 |
|----|------|------|
| review → bug_fix | `overall_result == REJECTED` | 审查不通过，进入修复回环（最多 3 轮） |
| bug_fix → review | 无条件 | 修复完成后重新审查 |
| review → test_audit | `== PASSED` | 审查通过，进入审计 |
| review → test_audit | `== BLOCK` | 审查阻塞（但非失败），走审计 + 集成测试后门控判定 |
| review → test_audit | `is_default` | 降级兜底：契约缺失或值不匹配时继续流程，不卡死 |
| gate_check → doc | `== PASSED` | 门控通过，生成文档 |
| gate_check → null | `== BLOCKED` | 门控不通过，暂停工作流 |

---

## 工作原理

### 8 节点工作流图（standard-feature）

每个节点由一种 Agent 角色执行，产出强类型 YAML 契约，驱动下游节点的条件分发：

| 节点 | Stage | 角色 | 指令文件 | 契约输出 | 驱动规则 |
|------|-------|------|----------|----------|----------|
| requirement | Stage 1 | PM | `references/LLM-01-requirement-drafter.md` | `{RUN}-stage1-requirement.yaml` | → develop（无条件） |
| develop | Stage 2 | DEV | `references/LLM-02-developer.md` | `{RUN}-stage2-codechange.yaml` | → review（无条件） |
| review | Stage 3 | REVIEWER | `references/LLM-03-code-reviewer.md` | `{RUN}-stage3-review.yaml` | overall_result → PASSED / REJECTED / BLOCK |
| bug_fix | — | DEV | `references/LLM-02-developer.md` | 回写 codechange 契约 | → review（回环，最多 3 轮） |
| test_audit | Stage 4 | TESTER | `references/LLM-04-test-engineer.md` | `{RUN}-stage4-testaudit.yaml` | → integration_test（无条件） |
| integration_test | Stage 5 | INTEG | `references/LLM-05-integration-tester.md` | `{RUN}-stage5-integration.yaml` | → gate_check（无条件） |
| gate_check | Stage 6 | PM_GATE | `references/LLM-06-project-manager.md` | `{RUN}-stage6-gate.yaml` | overall_result → PASSED / BLOCKED |
| doc_engineer | Stage 7 | DOC | `references/LLM-07-doc-engineer.md` | — | 终节点 |

> 注：前置初始化阶段 Stage 0 对应指令文件 `references/LLM-00-project-detect.md`。

### 契约协议：为什么不是"口口相传"

每个阶段完成后必须输出结构化 YAML，字段命名、类型、枚举值由 `template/contracts/` 下的 schema 文件定义。graph_runner.py 读取这些契约，解析关键字段，决定下一跳。

以 review 契约为例 —— **一个字段决定 4 条边的路由**：

```yaml
# autodev/contracts/{RUN}-stage3-review.yaml
version: "2.0"
overall_result: "PASSED"  # ← 这个字段决定了 review 节点后走哪条边
metrics:
  critical: 0
  major: 2
  minor: 1
```

| overall_result 值 | 匹配的边 | 走向 |
|-------------------|----------|------|
| `PASSED` | `review → test_audit`（条件边） | 进入审计 |
| `REJECTED` | `review → bug_fix`（条件边） | 修复回环（第 4 次触发 BLOCKED 熔断） |
| `BLOCK` | `review → test_audit`（条件边） | 进入审计但标记阻塞 |
| 契约文件缺失 / 值不匹配 | `review → test_audit`（is_default 降级边） | 不卡死，继续流程 |

### 引擎运行时

`scripts/graph_runner.py` 是轻量级 Graph 解析器（纯 Python 3，零外部依赖），工作循环：

1. **模板实例化** — `init` 将 `{RUN}` 替换为批次号，写入 `autodev/workflows/active-workflow.yaml`
2. **节点就绪检测** — `next` 检查目标节点 inputs 中的所有契约文件是否存在，缺失则阻断
3. **条件求值** — 读取上游契约，按条件表达式匹配（支持 `==`、`!=`、`>`、`in`、点号级联字段）
4. **降级兜底** — 无条件边匹配时走 `is_default` 边
5. **熔断防护** — `max_trigger_count` 超限时自动置 `BLOCKED`
6. **状态持久化** — 更新 `state.yaml` + 同步 `status.json`（供 status.sh 面板展示）

```bash
# 以下命令在 Stage 0 项目检测时自动执行，日常使用无需手动操作
# 仅在 CI/自动化管道中需要直接调用
# 从项目根目录执行时，将 <skill-dir> 替换为 skill 实际路径（安装方式一为 autodev/skills/autodev-flow）

# 初始化（选择模板）
python3 <skill-dir>/scripts/graph_runner.py init \
  <skill-dir>/template/workflows/standard-feature.workflow.yaml {RUN}

# 推进节点
python3 <skill-dir>/scripts/graph_runner.py next

# 重置工作流
python3 <skill-dir>/scripts/graph_runner.py reset
```

### 双模板策略

| 模板 | 场景 | 起始节点 | 初始化命令 |
|------|------|----------|------------|
| `standard-feature.workflow.yaml` | 常规功能开发 | requirement（含需求分析） | `python3 <skill-dir>/scripts/graph_runner.py init <skill-dir>/template/workflows/standard-feature.workflow.yaml {RUN}` |
| `quick-fix.workflow.yaml` | 紧急 BUG 修复 / 微小调整 | develop（跳过需求分析，直接修补） | `python3 <skill-dir>/scripts/graph_runner.py init <skill-dir>/template/workflows/quick-fix.workflow.yaml {RUN}` |

两套模板共享同一套节点定义和条件边逻辑，只差一个 requirement 节点。

> 完整 DSL 和条件表达式参考：[references/graph-reference.md](references/graph-reference.md)

---

## 所有用法

### 处理任务

```
# 单阶段执行
"使用 autodev-flow，执行 Stage 1，需求：新增用户注册"
"执行 Stage 1，更新需求 20260804-DEV-001：增加手机号注册"
"执行 Stage 2，执行开发任务"

# 批量执行，当天任务追踪文档中有多个任务时会批量执行，一批最多执行五个任务
"使用 autodev-flow，执行 Stage 2-6"    # 从开发到门控

# 完整工作流
"使用 autodev-flow 执行完整工作流"

# 指定任务
"使用 autodev-flow，处理 20260804-DEV-001"
```

### 查看状态

在**项目根目录**（包含 `autodev/` 工作区的目录）执行：

```bash
# macOS / Linux
bash autodev/status.sh              # 阶段状态面板
bash autodev/status.sh --tasks      # 任务状态
bash autodev/status.sh --watch      # 动态刷新（默认 10 秒）
bash autodev/status.sh --watch 5    # 自定义刷新间隔

# Windows PowerShell
powershell -ExecutionPolicy Bypass -File autodev\status.ps1
powershell -ExecutionPolicy Bypass -File autodev\status.ps1 -Tasks
powershell -ExecutionPolicy Bypass -File autodev\status.ps1 -Watch
powershell -ExecutionPolicy Bypass -File autodev\status.ps1 -Watch -Tasks
powershell -ExecutionPolicy Bypass -File autodev\status.ps1 -Watch -Interval 5
```

### 常见问题

**Q: Stage 7 为什么没执行？**
A: 门控检查：Stage 6 的 BUG > 0 或 DEV > 0 时自动跳过文档同步，等待修复后再跑。

**Q: autopush 卡片是什么？**
A: Stage 6 门控复核时，审计发现的异常项被自动回种为任务卡（`autopush-{RUN}-{TYPE}-{NNN}`），下一轮 Stage 2 接手修复——形成自动闭环。

**Q: 任务卡编号怎么读？**
A: `20260804-DEV-001` = 8 月 4 日的第 1 个开发任务。BUG / OPT / DEV 独立编号。

---

## 配置

Stage 0（项目检测）会自动从 skill 目录下的 `template/config.template.json` 生成 `autodev/config.json`，所有 `{{占位符}}` 均由 AI 自动填充。状态脚本 `status.sh` 和 `status.ps1` 也会在同一阶段从 skill 目录拷贝到 `autodev/` 根目录。仅在自动检测结果不准确时，才需要手工修正 `autodev/config.json` 中的个别字段。

<details>
<summary>完整字段说明（点击展开）</summary>

| 分组 | 字段 | 说明 |
|------|------|------|
| project | name / nameEn / workspace | 项目名 + 根目录 |
| modules | backend / frontend / app | 子模块目录名 |
| database | type / devFile / schemaSqlite / schemaMysql | 数据库配置 |
| techStack | backend / orm / frontend / language / cache | 技术栈 |
| ports | backend / frontend / redis | 服务端口 |
| api | prefix / healthCheck | API 配置 |
| docs | design / readme / agents | 文档路径 |
| docker | redisImage / containerName | Docker 配置 |

</details>

---

## 关键规则

- **防覆盖**：任务文件只能 Edit 追加，严禁 Write 覆盖
- **编号**：`{RUN}-{TYPE}-{NNN}`，回种卡前缀 `autopush-`
- **单次上限**：每轮最多 5 个任务，防超时
- **门控**：Stage 7 仅在 BUG=0 且 DEV=0 时执行
- **真相源**：`status.json` 是流水线状态唯一来源
- **时区**：统一北京时区 UTC+8
- **职责分离**：开发不审查、审查不修复、审计不写代码

---

## License

MIT
