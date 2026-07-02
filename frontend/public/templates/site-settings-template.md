---
# ============================================================
# 站点设置 md 导入模版（2026-07-01 DEV-005）
# ============================================================
#
# 用法：
#   1. 在管理后台「高级」tab 底部「博客信息导入」区块下载本模版
#   2. 按需修改 4 段（profile / blog / techstack / experience）的字段值
#   3. 上传回管理后台 → 整体原子提交（任一段校验失败全部回滚）
#
# 校验规则：
#   - 必须是有效的 YAML frontmatter（--- 开头/结尾）
#   - 仅支持 profile / blog / techstack / experience 4 个段名（拼错段名 → 报错）
#   - 每段字段必须严格匹配白名单（缺字段 / 多字段 / 类型错 → 报错并指明位置）
#
# 与 v5.0 settings 接口的关系：
#   - profile 段 → SettingsController.updateProfile（User 表）
#   - blog / techstack / experience 段 → SettingsController.updateBlog / updateTechstack / updateExperience
# ============================================================

# 个人资料（写入 user 表，走 /admin/settings/profile）
# 白名单字段：nickname, email, avatar, bio, intro, quote, footerText, location
profile:
  nickname: Corey
  email: ""
  avatar: ""
  bio: ""
  intro: ""
  quote: ""
  footerText: ""
  location: ""

# 站点信息（写入 site_settings.section=blog，走 /admin/settings/blog）
# 白名单字段：title, subtitle, description, copyright, logo
blog:
  title: Corey 博客
  subtitle: "Java / AI 应用工程师 · 专注 LLM 与多 Agent 协作"
  description: "记录后端工程、AI 应用、Agent 协作与个人成长。"
  copyright: "© 2026 Corey"
  logo: ""

# 技术栈（写入 site_settings.section=techstack，走 /admin/settings/techstack）
# 结构：{ groups: [ { label, items: [ { name, dim } ] } ] }
# groups 数组上限 20 个，每个 items 数组上限 50 个
techstack:
  groups:
    - label: 工作中常用
      items:
        - { name: "Java / Spring Boot / Spring Cloud", dim: false }
        - { name: "MyBatis / Spring Security", dim: false }
        - { name: "MySQL / Oracle / 高斯、达梦", dim: false }
        - { name: "Redis / Kafka", dim: false }
        - { name: "K8S / Docker / 华为云 CSE", dim: false }
        - { name: "Claude Code / Mavis Agent", dim: false }
        - { name: "Prompt 工程（Few-shot / CoT / System Prompt）", dim: false }
        - { name: "Ollama（qwen2.5 / deepseek 本地部署）", dim: false }
        - { name: "Prometheus + Grafana", dim: false }
        - { name: "CA 证书 / 接口加解密", dim: false }
    - label: 了解 / 学习中
      items:
        - { name: "Python / Shell", dim: true }
        - { name: "n8n（数据处理 / 报表自动化）", dim: true }
        - { name: "MCP 协议", dim: true }
        - { name: "ragflow（深度文档理解 + 检索增强）", dim: true }
        - { name: "Llama Factory（大模型微调）", dim: true }

# 个人经历（写入 site_settings.section=experience，走 /admin/settings/experience）
# 结构：{ items: [ { time, title, desc } ] }
# items 数组上限 50 条
experience:
  items:
    - time: "2026.03 — 2026.05"
      title: "某企业智能分析平台（NDA 涉密项目）· n8n + 主 Agent + 子 Agent 双层架构"
      desc: "所有推理内网；周报汇总 1h → 5min；主 Agent 调度 + 子 Agent 执行；理解 MCP 协议。"
    - time: "2025.05 — 2026.06（持续迭代中）"
      title: "账单分类工具 · Claude Code + Mavis Agent 协同研发"
      desc: "Python / CustomTkinter / SQLite / Ollama；「规则优先 → LLM 兜底」；System Prompt + JSON + 置信度；4 套 Agent 协作 Prompt 模板。"
    - time: "2024.09 — 2025.12"
      title: "某金融机构反洗钱系统 · SpringCloud + K8S"
      desc: "客户信息 + 风险名单；慢接口 2000ms → 200ms；日均 5w+ 笔交易风险查询。"
    - time: "2023.05 — 2024.08"
      title: "某省级政务一体化平台 · SpringCloud + Oracle"
      desc: "基础数据接入 + 接口加解密；门户单点登录；历史数据清洗。"

---
（下面是 Markdown body，可写可不写，上传时不会被解析）
============================================================

# 站点设置备份说明

这是模版的说明区段，**不会被后端解析**，仅作模版可读性。

上传此 md 文档后，后端会：
1. 校验 YAML frontmatter 格式（必须以 --- 开头/结尾）
2. 校验 4 段（profile / blog / techstack / experience）的字段白名单 + 类型
3. 整体原子更新 DB（任一段失败全部回滚 + Redis 缓存不清空）
4. 删除临时文件 `UPLOAD_DIR/settings-md-import/{uuid}.md`

字段命名约定：
- `profile` 段字段对应 `user` 表的 nickname / email / avatar / bio / intro / quote / footerText / location
- `blog` 段字段对应 `site_settings.section=blog` 的 JSON 内容
- `techstack` 段结构：`{ groups: [{ label, items: [{ name, dim }] }] }`
- `experience` 段结构：`{ items: [{ time, title, desc }] }`
