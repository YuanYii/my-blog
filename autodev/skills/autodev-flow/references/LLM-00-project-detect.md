# format: yaml-compact

role: 项目架构自动识别助手（Project Config Detector）
responsibility: 扫描项目目录结构，自动识别技术栈和架构，生成 `autodev/config.json`，并初始化工作区（拷贝模板文件 + 创建目录 + 拷贝状态脚本）
do_not: 不修改项目业务代码，只生成配置文件和初始化工作区结构

flow:
  - step: 1
    name: 扫描项目目录结构
    action: 列出根目录及一级子目录所有文件和目录，识别关键配置文件和子模块结构
    module_scan:
      depth: 2
      targets: ["backend/", "frontend/", "app/", "packages/", "modules/"]
      sub_config_files: ["pom.xml", "build.gradle", "package.json", "go.mod"]
    config_files:
      pom.xml: Java/Maven
      build.gradle: Java/Gradle
      package.json: Node.js/JS/TS
      "requirements.txt|pyproject.toml": Python
      go.mod: Go
      Cargo.toml: Rust
      Gemfile: Ruby

  - step: 2
    name: 识别后端架构
    sub:
      java_maven:
        - 读根 pom.xml → 提取 `<artifactId>` 作项目名
        - 检查 `<modules>` 标签 → 识别子模块
        - 读 `application.yml`/`application.properties` → 识别框架（Spring Boot/Quarkus/Micronaut）
        - 检查 mybatis/mybatis-plus/jpa/hibernate 依赖 → 识别 ORM
      java_gradle:
        - 读 build.gradle/build.gradle.kts → 提取依赖
        - 检查 `org.springframework.boot` 依赖 → Spring Boot
        - 检查 `org.mybatis.spring.boot` 依赖 → MyBatis
        - 检查 `spring-boot-starter-data-jpa` 依赖 → JPA/Hibernate
      nodejs:
        - 读 package.json → 提取 `name` 作项目名
        - 检查依赖 → 识别框架（Express/Koa/NestJS/Fastify）
        - 检查 tsconfig.json → 是否 TypeScript
        - 检查 prisma/typeorm/sequelize/knex/bookshelf 等依赖 → 识别 ORM
      python:
        - 读 requirements.txt/pyproject.toml → 识别框架（Django/Flask/FastAPI）
        - 检查 manage.py → Django 标志
        - 检查 sqlalchemy/peewee/pony 等依赖 → 识别 ORM

  - step: 3
    name: 识别前端架构
    action: 检查 frontend/ 目录或根目录下 package.json
    frameworks: [React, Vue, Angular, Nuxt, Next.js]
    config_files:
      - nuxt.config.ts
      - next.config.js
      - vite.config.ts
      - vue.config.js
      - angular.json
      - svelte.config.js
      - .umirc.ts
      - umi.config.ts

  - step: 4
    name: 识别数据库
    action: 在配置文件中扫描数据库连接字符串
    scan_locations:
      - application.yml
      - application.properties
      - .env
      - .env.local
    mapping:
      "sqlite:": SQLite
      "mysql|mariadb": MySQL
      "postgresql|postgres": PostgreSQL
      mongodb: MongoDB
      redis: Redis（缓存）

  - step: 5
    name: 识别端口
    backend: 
      - Spring Boot: application.yml 的 `server.port` / application.properties 的 `server.port`
      - Node.js: 环境变量 PORT / .env 中 PORT / package.json scripts 中的 --port 参数
      - Python: 环境变量 PORT / .env 中 PORT
      - 默认值: Spring Boot 8080, Express 3000, Flask 5000, Django 8000, FastAPI 8000
    frontend: 
      - Nuxt: nuxt.config.ts 的 `devServer.port`
      - Vite: vite.config.ts 的 `server.port`
      - Next.js: package.json scripts 中的 -p 参数，默认 3000
      - Angular: angular.json 的 `serve.options.port`
      - 未识别前端框架: 默认 3000
    redis: 默认 6379

  - step: 6
    name: 识别 API 前缀
    check: context-path / baseURL / prefix 配置
    common: ["/api/v1", "/api", "/v1"]

  - step: 7
    name: 识别文档路径
    locations: [README.md, docs/design/, AGENTS.md]

  - step: 8
    name: 生成 config.json
    output: autodev/config.json
    hint: 将前 7 步识别结果按以下规则填充到 config.template.json 对应的完整字段布局中
    nameEn_rule: 无法自动推断英文名时，用 project.name 的拼音首字母缩写或直接使用 project.name
    schema_paths: database.schemaSqlite 和 database.schemaMysql 必须填充（未识别到 schema 文件时给定合理默认路径如 docs/sql/schema.sqlite.sql 和 docs/sql/schema.mysql.sql，标注"待人工确认"）
    language_list: techStack.language 从以下来源推断——java_maven/java_gradle → "Java"，nodejs+tsconfig.json → "TypeScript"，nodejs 无 tsconfig → "JavaScript"，python → "Python"，go → "Go"，rust → "Rust"，ruby → "Ruby"
    field_completeness: 以 config.template.json 为对照基准，所有顶层字段必须存在，缺失则创建并填默认值

  - step: 9
    name: 校验 config.json
    action: 验证生成的 config.json 合法性和完整性
    validate:
      - 文件存在且为合法 JSON
      - project.name 不为空
      - project.nameEn 不为空（无法自动生成时回退为 project.name）
      - techStack.backend 或 techStack.frontend 至少一个已识别
      - database.type 不为空且不为"待确认"——无法识别时至少设为 SQLite 作为最小可用默认值（devFile 设为 "data/dev.db"，schemaSqlite/schemaMysql 给占位路径并标注"待人工确认"）
      - 所有在 config.template.json 中出现的顶层字段必须存在（允许值为空字符串 "" 或空数组 []，不允许整个字段缺失）
      - ports 为数字、docs 为路径字符串
      - 以 config.template.json 为对照基准，逐字段比对完整性
    blocking_fields:
      description: 以下字段为"待确认"或空值时继续生成但输出 ⚠️ 警告，建议人工补充
      list: [modules.app, database.schemaSqlite, database.schemaMysql, docker.containerName]

output_config_json:
  project: { name, nameEn, workspace }
  modules: { backend, frontend, app, ... }
  database: { type, devFile, schemaSqlite, schemaMysql }
  techStack: { backend, orm, frontend, language: [], cache }
  ports: { backend: int, frontend: int, redis: 6379 }
  api: { prefix, healthCheck }
  docs: { design, readme, agents }
  docker: { redisImage: "redis:7-alpine", containerName }

rules:
  project_name_priority:
    1: pom.xml 的 `<artifactId>`
    2: package.json 的 `name`
    3: 根目录名
  port_detection_priority:
    1: 配置文件中的显式配置
    2: 框架默认值（Spring Boot:8080, Express:3000, ...）
  api_prefix_detection:
    1: context-path 配置
    2: Controller 路径中的公共前缀
    3: 默认 `/api`

exceptions:
  无法识别技术栈: 使用通用配置，标注"待确认"
  配置文件不存在: 使用框架默认值
  多个项目混合: 以根目录的配置文件为准

exec_commands:
  scan: "ls -la  # 扫描项目根目录，列出所有文件和一级子目录"
  workspace: "pwd  # 获取当前项目根目录绝对路径，精准填入 project.workspace 字段"
  config_read: 根据 scan 结果，读取识别到的配置文件内容（由 AI 决定读哪些文件，如 cat pom.xml / cat package.json 等）
  generate: 由 AI 根据前 8 步规则填充生成 config.json
  validate: 由 AI 按 Step 9 校验规则验证 config.json 合法性和字段完整性

post_actions:
  - mkdir -p autodev/{auto_iteration,auto_audit,contracts,workflows}
  - 将 autodev-flow skill 的 template/auto_iteration/20260000tmp.md 拷贝到 autodev/auto_iteration/20260000tmp.md
  - 将 autodev-flow skill 的 scripts/status.sh 拷贝到 autodev/status.sh
  - 将 autodev-flow skill 的 scripts/status.ps1 拷贝到 autodev/status.ps1
  - chmod +x autodev/status.sh
  - 自动衔接 v2.0 工作流初始化：运行 `python3 <autodev-flow-skill-dir>/scripts/graph_runner.py init <template_path> <RUN>`
    - 若用户提供了需求/功能开发指令，使用 `template/workflows/standard-feature.workflow.yaml`
    - 若用户仅请求 BUG 快捷修补，使用 `template/workflows/quick-fix.workflow.yaml`
  cp_hint: autodev-flow skill 目录路径 = SKILL.md 所在目录。若无法确定绝对路径，输出提示告知用户手动拷贝并执行："请手动执行 cp <autodev-flow-skill-dir>/template/auto_iteration/20260000tmp.md autodev/auto_iteration/20260000tmp.md && cp <autodev-flow-skill-dir>/scripts/status.sh autodev/status.sh && chmod +x autodev/status.sh && python3 <autodev-flow-skill-dir>/scripts/graph_runner.py init <autodev-flow-skill-dir>/template/workflows/standard-feature.workflow.yaml <RUN>"
