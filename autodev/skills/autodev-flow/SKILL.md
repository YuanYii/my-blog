---
name: autodev-flow
description: "通用的自动化开发工作流，将 SDLC 拆为 8 个阶段，纯 Markdown 指令交付，支持 Codex/Claude/Cursor 等多种 Agent。使用此技能执行完整开发工作流、单阶段触发、或查看工作流状态。"
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
autodev/
├── config.json                # 项目配置（Stage 0 从模板生成）
├── status.json                # 工作流状态（唯一真相源）
├── status.sh                  # 状态查看脚本（Stage 0 自动拷贝）
├── auto_iteration/            # 任务跟踪文档
│   └── {YYYYMMDD}.md         # 每日任务卡 + 处理报告 + 状态表
├── auto_audit/                # 审计报告
│   └── {YYYYMMDD}/
│       ├── {RUN}-stage3.md  # 代码审查报告
│       ├── {RUN}-stage4.md  # 测试审计报告
│       ├── {RUN}-stage5.md  # 集成测试报告
│       └── {RUN}-stage6.md  # 门控文件
└── skills/autodev-flow/       # 本工作流指令
    ├── SKILL.md
    ├── docs/                  # 设计文档
    ├── template/              # 配置模板
    ├── references/            # 阶段指令
    └── scripts/               # 工具脚本
```

## 关键规则

1. **防覆盖**：任务文件只能 Edit 追加，严禁 Write 覆盖
2. **任务编号**：`{RUN}-{TYPE}-{NNN}`（如 `20260707-DEV-001`）
3. **回种卡**：`autopush-{RUN}-{TYPE}-{NNN}`（Stage 6 生成）
4. **单次上限**：最多 5 个任务（避免超时）
5. **门控检查**：Stage 7 仅在 Stage 6 的 BUG=0 且 DEV=0 时执行
6. **闭环迭代**：Stage 6 异常项回种为任务卡，下一轮 Stage 2 接手处理
7. **时区**：所有时间字段用北京时区 UTC+8
