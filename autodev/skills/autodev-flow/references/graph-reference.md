# v2.0 Graph DSL & 契约协议参考

> 本文件包含 v2.0 Graph 工作流引擎的详细定义。仅在需要理解或修改工作流图 DSL、契约协议、运行时行为时读取。

## 工作流图 DSL

工作流通过声明式的 `nodes`（节点集）与 `edges`（边集）定义：

```yaml
nodes:
  - id: requirement
    name: "需求分析"
    agent_role: "PM"
    instruction_file: "references/LLM-01-requirement-drafter.md"
    inputs: []
    outputs:
      - "autodev/contracts/{RUN}-stage1-requirement.yaml"

  - id: develop
    name: "代码实现"
    agent_role: "DEV"
    instruction_file: "references/LLM-02-developer.md"
    inputs:
      - "autodev/contracts/{RUN}-stage1-requirement.yaml"
    outputs:
      - "autodev/contracts/{RUN}-stage2-codechange.yaml"

  - id: review
    name: "代码审查"
    agent_role: "REVIEWER"
    instruction_file: "references/LLM-03-code-reviewer.md"
    inputs:
      - "autodev/contracts/{RUN}-stage2-codechange.yaml"
    outputs:
      - "autodev/contracts/{RUN}-stage3-review.yaml"

  - id: bug_fix
    name: "缺陷修补"
    agent_role: "DEV"
    instruction_file: "references/LLM-02-developer.md"
    inputs:
      - "autodev/contracts/{RUN}-stage3-review.yaml"
    outputs:
      - "autodev/contracts/{RUN}-stage2-codechange.yaml"

  - id: test_audit
    name: "需求验收审计"
    agent_role: "TESTER"
    instruction_file: "references/LLM-04-test-engineer.md"
    inputs:
      - "autodev/contracts/{RUN}-stage3-review.yaml"
    outputs:
      - "autodev/contracts/{RUN}-stage4-testaudit.yaml"

  - id: integration_test
    name: "集成测试"
    agent_role: "INTEGRATION_TESTER"
    instruction_file: "references/LLM-05-integration-tester.md"
    inputs:
      - "autodev/contracts/{RUN}-stage4-testaudit.yaml"
    outputs:
      - "autodev/contracts/{RUN}-stage5-integration.yaml"

  - id: gate_check
    name: "门控复核"
    agent_role: "PM_GATE"
    instruction_file: "references/LLM-06-project-manager.md"
    inputs:
      - "autodev/contracts/{RUN}-stage3-review.yaml"
      - "autodev/contracts/{RUN}-stage4-testaudit.yaml"
      - "autodev/contracts/{RUN}-stage5-integration.yaml"
    outputs:
      - "autodev/contracts/{RUN}-stage6-gate.yaml"

  - id: doc_engineer
    name: "文档同步"
    agent_role: "DOC_ENGINEER"
    instruction_file: "references/LLM-07-doc-engineer.md"
    inputs:
      - "autodev/contracts/{RUN}-stage6-gate.yaml"
    outputs: []

edges:
  - from: requirement
    to: develop

  - from: develop
    to: review

  - from: review
    to: bug_fix
    max_trigger_count: 3
    condition:
      contract_ref: "autodev/contracts/{RUN}-stage3-review.yaml"
      field: "overall_result"
      operator: "=="
      value: "REJECTED"

  - from: bug_fix
    to: review

  - from: review
    to: test_audit
    condition:
      contract_ref: "autodev/contracts/{RUN}-stage3-review.yaml"
      field: "overall_result"
      operator: "=="
      value: "PASSED"

  - from: review
    to: test_audit
    condition:
      contract_ref: "autodev/contracts/{RUN}-stage3-review.yaml"
      field: "overall_result"
      operator: "=="
      value: "BLOCK"

  - from: review
    to: test_audit
    is_default: true

  - from: test_audit
    to: integration_test

  - from: integration_test
    to: gate_check

  - from: gate_check
    to: doc_engineer
    condition:
      contract_ref: "autodev/contracts/{RUN}-stage6-gate.yaml"
      field: "overall_result"
      operator: "=="
      value: "PASSED"
```

### 边属性说明

| 属性 | 类型 | 说明 |
|------|------|------|
| `from` | string | 源节点 ID |
| `to` | string | 目标节点 ID |
| `condition` | object | 条件表达式（`contract_ref`, `field`, `operator`, `value`） |
| `max_trigger_count` | int | 熔断保护最大转换触发上限（达到转换上限后，下一次尝试转换时触发熔断阻断并置为 `BLOCKED`） |
| `is_default` | bool | 降级兜底边：当所有条件边均未匹配时走此边 |

## 预置工作流模板

| 模板文件 | 适用场景 | 拓扑 |
|----------|----------|------|
| `template/workflows/standard-feature.workflow.yaml` | 常规功能开发 | Requirement → Develop → Review ⇄ BugFix → TestAudit → IntegrationTest → GateCheck → DocEngineer |
| `template/workflows/quick-fix.workflow.yaml` | 紧急 BUG 修复/微小调整 | Develop → Review ⇄ BugFix → TestAudit → IntegrationTest → GateCheck → DocEngineer |

## 契约协议

阶段间通过结构化 YAML 契约传递产物：

| 契约文件 | 生产者 | 消费者 |
|----------|--------|--------|
| `autodev/contracts/{RUN}-stage1-requirement.yaml` | Stage 1 (PM) | Stage 2 (DEV) |
| `autodev/contracts/{RUN}-stage2-codechange.yaml` | Stage 2 (DEV) | Stage 3 (REVIEWER) |
| `autodev/contracts/{RUN}-stage3-review.yaml` | Stage 3 (REVIEWER) | Graph 引擎（条件分支）+ Stage 4/6 |
| `autodev/contracts/{RUN}-stage4-testaudit.yaml` | Stage 4 (TESTER) | Stage 5 (INTEGRATION) |
| `autodev/contracts/{RUN}-stage5-integration.yaml` | Stage 5 (INTEGRATION) | Stage 6 (GATE) |
| `autodev/contracts/{RUN}-stage6-gate.yaml` | Stage 6 (GATE) | Stage 7 (文档同步) 门控判断 |

契约模板文件位于 `template/contracts/`：
- `requirement.schema.yaml` — 需求拟定契约
- `code-change.schema.yaml` — 代码变更契约
- `review.schema.yaml` — 审查/门控契约
- `test-audit.schema.yaml` — 需求验收审计契约
- `integration-test.schema.yaml` — 集成测试契约

## Graph Parser 运行时

`scripts/graph_runner.py` 工作循环：

1. **模板实例化**：将 `{RUN}` 替换为实际批次号，生成 `autodev/workflows/active-workflow.yaml`
2. **就绪检测**：检查当前节点的前置契约文件是否存在
3. **条件求值与降级**：读取产出契约，按条件表达式匹配；无匹配时走 `is_default` 降级边
4. **熔断防护与重置**：`max_trigger_count` 超限时阻断并置 `BLOCKED`，可执行 `reset` 子命令恢复
5. **状态持久化**：更新 `state.yaml` 并映射写回 `status.json`（兼容 status.sh 面板）

### 命令参考

```bash
# 从 skill 目录或项目根目录执行，将 <skill-dir> 替换为 skill 实际路径

# 初始化工作流 (支持带 --force-skip 绕过前置校验警告阻断)
python3 <skill-dir>/scripts/graph_runner.py init <skill-dir>/template/workflows/standard-feature.workflow.yaml {RUN} [--force-skip]

# 推进到下一节点
python3 <skill-dir>/scripts/graph_runner.py next

# 重置工作流 BLOCKED 状态与熔断计数
python3 <skill-dir>/scripts/graph_runner.py reset

# 查看状态
bash autodev/status.sh
```
