---
name: autodev-flow
description: "通用的自动化开发工作流（v2.0），基于契约驱动的 Graph 引擎动态编排开发流程，纯 Markdown 指令交付，支持 Codex/Claude/Cursor 等多种 Agent。使用此技能执行完整开发工作流、单阶段触发、或查看工作流状态。"
---

# AutoDev Flow

一套通用的自动化开发工作流，将软件开发生命周期（SDLC）拆分为 8 个阶段，以标准化 Markdown 指令文件交付，可被多种 AI Agent 加载执行。

## 快速开始

```bash
# 检测项目架构（首次使用）
"使用 autodev-flow，检测项目架构"

# 提交需求
"使用 autodev-flow，需求：新增文章收藏功能"

# 执行完整工作流
"使用 autodev-flow 执行完整工作流"

# 执行单个阶段
"使用 autodev-flow，执行 Stage 2 开发"
```

### v2.0 Graph 引擎命令

以下命令在 Stage 0 项目检测时自动执行，日常使用无需手动操作。仅在 CI/自动化管道中需要直接调用：

```bash
# 初始化工作流图（根据需求类型选择模板）
# <skill-dir> 为 skill 所在目录（安装方式一: autodev/skills/autodev-flow；方式二: 取决于 CLI 默认路径）
python3 <skill-dir>/scripts/graph_runner.py init \
  <skill-dir>/template/workflows/standard-feature.workflow.yaml \
  {RUN}

# 每阶段 Agent 执行完毕后推进到下一节点
python3 <skill-dir>/scripts/graph_runner.py next

# 查看当前节点与状态
bash autodev/status.sh
```

## 工作流阶段

| Stage | 角色 | 指令文件 |
|-------|------|----------|
| 0 | 项目检测 | `references/LLM-00-project-detect.md` |
| 1 | 需求拟定 | `references/LLM-01-requirement-drafter.md` |
| 2 | 代码实现 | `references/LLM-02-developer.md` |
| 3 | 代码审查 | `references/LLM-03-code-reviewer.md` |
| 4 | 静态审计 | `references/LLM-04-test-engineer.md` |
| 5 | 集成测试 | `references/LLM-05-integration-tester.md` |
| 6 | 门控复核 | `references/LLM-06-project-manager.md` |
| 7 | 文档同步 | `references/LLM-07-doc-engineer.md` |

每个阶段的详细指令请读取对应的 reference 文件。

> **v2.0 升级**：本工作流已从静态顺序流水线升级为**契约驱动的动态 Graph 工作流**。通过条件边、修复回环、降级兜底实现动态编排。

## v2.0 Graph 工作流引擎

详细定义见 [references/graph-reference.md](references/graph-reference.md)。

快速使用：
- `python3 <skill-dir>/scripts/graph_runner.py init <模板路径> <RUN>` — 初始化工作流（前置条件：需 `autodev/config.json` 存在）
- `python3 <skill-dir>/scripts/graph_runner.py next [--force-skip]` — 推进到下一节点
- `python3 <skill-dir>/scripts/graph_runner.py status` — 查看当前状态
- `python3 <skill-dir>/scripts/graph_runner.py reset` — 重置工作流
- `python3 <skill-dir>/scripts/graph_runner.py check-update` — 检查 Skill 是否有最新版本
- `python3 <skill-dir>/scripts/graph_runner.py upgrade` — 执行 Skill 平滑升级（遵循产物隔离原则，不触碰 contracts 契约数据）
- `python3 <skill-dir>/scripts/graph_runner.py rollback` — 升级异常时从快照备份回滚 Skill
- 每个 Agent 完成后写入对应契约文件（`autodev/contracts/{RUN}-stage*.yaml`）
- review 契约的 `overall_result` 驱动分支：PASSED→test_audit, REJECTED→bug_fix
- 无契约或值不匹配时走 `is_default` 降级边


## 配置

- 配置模板：`template/config.template.json`
- Stage 0 检测完成后会自动生成 `autodev/config.json`，仅在自动检测不准确时手动补充

## 查看状态

```bash
bash autodev/status.sh                  # 阶段状态面板（需 jq）
bash autodev/status.sh --tasks          # 展示任务状态
bash autodev/status.sh --watch          # 动态刷新（默认 10 秒）
bash autodev/status.sh --watch 5        # 自定义刷新间隔
bash autodev/status.sh --watch --tasks  # 刷新 + 任务状态
```

## 数据流

```
# 运行时工作区（由 graph_runner.py init 在项目根目录生成）
autodev/
├── config.json                # 项目配置（Stage 0 从模板生成）
├── status.json                # 工作流状态（兼容 status.sh 面板）
├── state.yaml                 # v2.0 全局状态机（记录 overall_status、run_id、edge_trigger_counts 等）
├── status.sh                  # 状态查看脚本（Stage 0 自动拷贝）
├── contracts/                 # v2.0 阶段产物契约（Agent 按阶段写入）
│   ├── {RUN}-stage1-requirement.yaml
│   ├── {RUN}-stage2-codechange.yaml
│   ├── {RUN}-stage3-review.yaml
│   ├── {RUN}-stage4-testaudit.yaml
│   ├── {RUN}-stage5-integration.yaml
│   ├── {RUN}-stage6-gate.yaml
│   └── {RUN}-stage7-doc_engineer.yaml
├── workflows/                 # v2.0 工作流图实例（由 graph_runner.py init 生成）
│   └── active-workflow.yaml
├── auto_iteration/            # 任务跟踪文档
│   └── {YYYYMMDD}.md         # 每日任务卡 + 处理报告 + 状态表

# 本工作流技能目录
autodev-flow/
├── SKILL.md                   # 本文件
├── agents/                    # Agent 元数据配置
├── scripts/                   # 工具脚本
│   ├── graph_runner.py        # v2.0 Graph Parser 调度引擎
│   ├── status.sh              # 状态面板脚本
│   └── status.ps1             # Windows PowerShell 状态面板
├── references/                # LLM 阶段指令
│   ├── LLM-01-requirement-drafter.md
│   ├── LLM-02-developer.md
│   ├── LLM-03-code-reviewer.md
│   ├── LLM-04-test-engineer.md
│   ├── LLM-05-integration-tester.md
│   ├── LLM-06-project-manager.md
│   ├── LLM-07-doc-engineer.md
│   └── graph-reference.md     # v2.0 图引擎参考
└── template/                  # 模板
    ├── contracts/             # 契约 Schema 模板
    ├── workflows/             # 工作流图模板
    └── config.template.json   # 配置模板
```
## 关键规则

1. **防覆盖**：任务文件只能 Edit 追加，严禁 Write 覆盖
2. **任务编号**：`{RUN}-{TYPE}-{NNN}`（如 `20260707-DEV-001`）
3. **回种卡**：`autopush-{RUN}-{TYPE}-{NNN}`（Stage 6 生成）
4. **单次上限**：最多 5 个任务（避免超时）
5. **门控检查**：Stage 7 仅在 Stage 6 的 BUG=0 且 DEV=0 时执行
6. **闭环迭代**：Stage 6 异常项回种为任务卡，下一轮 Stage 2 接手处理
7. **时区**：所有时间字段用北京时区 UTC+8

### v2.0 新增规则

8. **图引擎驱动**：使用 `graph_runner.py init/next` 驱动工作流状态流转，而非手动跟踪阶段
9. **契约协议**：Agent 完成后必须输出对应阶段的 YAML 契约文件（存至 `autodev/contracts/`）
10. **条件分支**：review 契约的 `overall_result` 字段决定下一节点（PASSED→test_audit, REJECTED→bug_fix）
11. **熔断保护**：`max_trigger_count` 超限时自动阻断并置 `BLOCKED`，防止死循环
12. **降级兜底**：契约文件缺失或值不匹配时，走 `is_default` 降级边继续流程
13. **模板选择**：根据需求类型选择工作流模板（常规功能→standard-feature，紧急修复→quick-fix）
