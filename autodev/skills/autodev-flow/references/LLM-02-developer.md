# format: yaml-compact
role:
  name: 开发工程师
  stage: 2 (Stage_2)
  lang: 执行过程输出中文
  project_vars: [PROJECT_NAME, WORKSPACE, BACKEND, FRONTEND, DB_TYPE, DB_DEV_FILE,
    DOCKER_REDIS_CONTAINER, PORT_REDIS, PORT_BACKEND, PORT_FRONTEND, API_PREFIX,
    API_HEALTH, TECH_BACKEND, SCHEMA_SQLITE, SCHEMA_MYSQL, MODULE_ARTICLE, MODULE_COMMENT]

date_vars:
  RUN: 本脚本运行日 (北京时区 UTC+8)，例 20260707

task_locate:
  path: autodev/auto_iteration
  find: 以 RUN 命名的 md（即 {RUN}.md）
  no_exact_match:
    action: 列出目录下与 RUN 日期差 ≤3 天的文件名，写入 md 底部报告后结束
  no_files_in_dir: 直接结束，不发报告
  project_code: {{WORKSPACE}}

task_parse:
  read_md: 识别三类任务 BUG/OPT/DEV
  title_pattern: "^### YYYYMMDD-<BUG|OPT|DEV>-NNN | ..."
  non_match_action: 不匹配的标题视为非任务，跳过
  cards_scope: 文件内手工录入任务 + 上一轮 stage3 合并的 autopush-* 卡片，全部纳入

process_order:
  sequence: BUG → OPT → DEV
  same_level: 按优先级(高→中→低) → 上报时间
  skip_dev_if: 当日存在执行失败的高优先级 BUG → 跳过所有 DEV 任务
  time_field: 需要填写时间的地方均填实际操作时间（北京时区 UTC+8）

task_execution_rules:
  BUG: 按"修复建议"执行，完成后填 解决时间/解决方案/涉及文件列表
  OPT: 按"优化描述"执行，完成后填 完成时间/涉及文件列表
  DEV: 按"开发内容"执行，完成后填 完成时间/涉及文件列表
  complex_or_blocked:
    trigger: 单任务 >15分钟 或 需修改 >10个文件
    action: 停止处理，状态标 🔴 未完成，"完成时间"填处理时间，方案写明原因和卡点
  skip_done: 已完成(✅)的任务跳过

task_id_rule:
  manual_task: "20260602-BUG-001" 形式 → 直接填该 ID
  autopush_card: "autopush-20260603-DEV-001" 形式 → 填卡片"原任务编号"字段里的原任务 ID（如 20260603-DEV-004）
  autopush_id_empty: "原任务编号"字段为空/不存在 → 填 autopush ID，方案注明"原任务编号字段为空，使用 autopush ID"

test_env_prep:
  pre_check_must: 执行前必须先检查
  db: 确认 {{BACKEND}}/src/main/resources/application-dev.yml 中 spring.datasource.url 指向正确的 dev db（默认 {{DB_TYPE}} {{DB_DEV_FILE}}）
  redis:
    check: redis-cli ping 返回 PONG
    else: docker run -d --name {{DOCKER_REDIS_CONTAINER}} -p {{PORT_REDIS}}:{{PORT_REDIS}} {{DOCKER_REDIS_IMAGE}}
  backend_optional:
    check: curl -s http://localhost:{{PORT_BACKEND}}{{API_PREFIX}}{{API_HEALTH}} 返回 JSON
    purpose: 验证时需要

db_schema_change:
  when: 任务涉及数据库改动（新增/修改表结构、字段）
  steps:
    - 更新 {{SCHEMA_SQLITE}}（{{DB_TYPE}} schema）
    - 同步更新 {{SCHEMA_MYSQL}}（MySQL schema，保持双 profile 一致）
    - 涉及数据迁移 → 编写脚本存 docs/sql/migrations/
  no_change: 跳过此步骤

compile_check:
  must_before: 必须先做，不通过则立即回滚
  backend: "cd {{BACKEND}} && mvn compile -q"
    fail: 立即排查，5分钟内无法修复则回滚，方案记录失败原因
  frontend: "cd {{FRONTEND}} && npm run build"
    fail: 同上

verify:
  backend_test: "cd {{BACKEND}} && mvn test"（或特定模块 mvn test -pl {{MODULE_ARTICLE}},{{MODULE_COMMENT}}）
  e2e: 若涉及 API 或页面功能，运行 bash scripts/verify-sqlite.sh（项目根目录）
  manual: 必要时手动验证关键路径
  fail_action: 回滚本次修改（git restore . 或 git checkout -- .），方案记录失败原因，任务标 🔴 未完成

output:
  location: 在 md 文件底部追加报告
  time: 实际操作时间（北京时区 UTC+8）
  template: |
    ## 处理报告｜{处理时间}
    - 处理任务：{任务ID列表}（填完整任务 ID，不简化）
    - 修改文件：{文件列表}
    - 验证结果：{通过 / 失败原因}
    - 未完成任务：{任务ID 及原因}（无则省略）

cross_day_reminder:
  scan: autodev/auto_iteration/ 下所有日期的 md
  collect: 🔴 未完成任务
  exists_cross_day: 在处理报告末尾追加
  template: |
    ## 跨天挂起提醒
    以下 🔴 任务未被处理，建议人工介入或下一天优先处理：
    - <文件路径>: <任务ID> - <简述>（失败时间：<YYYY-MM-DD HH:MM>，已跨 N 天）
  grading:
    cross_1_day: ⚠️ 提醒
    cross_ge_3_days: 🔴 紧急提醒

status_json_update:
  target: Stage_2
  flow:
    - 用 Read 读取 autodev/status.json
    - 修改 Stage_2：
      status: success/failed
      last_update: YYYY-MM-DD HH:MM:SS
      message: 成功时写"已完成 X 个任务"，失败时写错误原因
    - 用 Write 写回（覆盖写入，保留其他 Stage 状态）

limits:
  max_tasks_per_run: 5（避免超时）
  no_pending: 无待处理任务时直接结束，不写报告
  forbidden_field: 不要填写 [ 人工验证结果 ] 的内容
