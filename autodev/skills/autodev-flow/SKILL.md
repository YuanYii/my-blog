---
name: autodev-flow
description: "Automated software development workflow with 8 stages including project detection, requirement drafting, development, code review, test audit, integration testing, project management gate, and documentation sync. Use when user wants to execute the full SDLC workflow, run any specific stage, or manage the autodev flow."
---

# AutoDev Flow

A complete SDLC automation workflow with 8 stages, executed sequentially or individually.

## Quick Start

```bash
# Detect project architecture
"使用 autodev-flow，检测项目架构"

# Execute full workflow
"使用 autodev-flow 执行完整工作流"

# Execute single stage
"使用 autodev-flow，执行 Stage 2 开发"

# Draft requirements
"使用 autodev-flow，需求：新增文章收藏功能"
```

## Workflow Stages

| Stage | Role | Reference File |
|-------|------|----------------|
| 0 | Project Detector | `references/LLM-00-project-detect.md` |
| 1 | Requirement Drafter | `references/LLM-01-requirement-drafter.md` |
| 2 | Developer | `references/LLM-02-developer.md` |
| 3 | Code Reviewer | `references/LLM-03-code-reviewer.md` |
| 4 | Test Engineer | `references/LLM-04-test-engineer.md` |
| 5 | Integration Tester | `references/LLM-05-integration-tester.md` |
| 6 | Project Manager | `references/LLM-06-project-manager.md` |
| 7 | Doc Engineer | `references/LLM-07-doc-engineer.md` |

## Stage Details

For detailed instructions per stage, read the corresponding reference file:
- Stage 0: `references/LLM-00-project-detect.md` - Project architecture detection
- Stage 1: `references/LLM-01-requirement-drafter.md` - Requirement drafting
- Stage 2: `references/LLM-02-developer.md` - Development implementation
- Stage 3: `references/LLM-03-code-reviewer.md` - Code quality review
- Stage 4: `references/LLM-04-test-engineer.md` - Test audit
- Stage 5: `references/LLM-05-integration-tester.md` - Integration testing
- Stage 6: `references/LLM-06-project-manager.md` - Project management gate
- Stage 7: `references/LLM-07-doc-engineer.md` - Documentation sync

## Configuration

Read `config.json` from the skill directory for project-specific settings (modules, ports, tech stack).

## Data Flow

```
autodev/
├── config.json              # Project config (shared)
├── status.json              # Workflow status
├── auto_iteration/          # Task tracking
│   └── {YYYYMMDD}.md       # Daily task cards
├── auto_audit/              # Audit reports
│   └── {YYYYMMDD}/
│       ├── stage3.md       # Code review
│       ├── stage4.md       # Test audit
│       ├── stage5.md       # Integration test
│       └── stage6.md       # Gate file
└── skills/autodev-flow/    # This skill
```

## Key Rules

1. **Anti-overwrite**: Never use Write to overwrite task files; always use Edit to append
2. **Task numbering**: `{RUN}-{TYPE}-{NNN}` (e.g., `20260707-DEV-001`)
3. **Max 5 tasks per execution** (avoid timeout)
4. **Gate check**: Stage 7 only runs if Stage 6 has BUG:0, DEV:0
