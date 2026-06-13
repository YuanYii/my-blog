-- ============================================================
-- Migration: site_settings 新增 techstack / experience 两个 section
-- 日期: 2026-06-13
-- 说明: 「关于我」页面的技术栈、个人经历改为后台可维护
--   - 后台读写: /api/v1/admin/settings/{techstack,experience} (GET/PUT)
--   - 前台公开读: /api/v1/public/settings/{techstack,experience} (GET)
--   - PUBLIC_SECTIONS 白名单已在 SiteSettingsService 加入这两段
-- 兜底: SiteSettingsService.@PostConstruct 启动时若缺失会自动补默认值，
--       本脚本用于全新部署/手动初始化时显式 seed。
-- 注意: section 列 VARCHAR(32)，'techstack'(9) / 'experience'(10) 均未超长。
-- ============================================================

-- techstack：分组 + 标签（dim 表示弱化显示）
INSERT INTO `site_settings` (`section`, `data`) VALUES
  ('techstack', JSON_OBJECT(
    'groups', JSON_ARRAY(
      JSON_OBJECT(
        'label', '工作中常用',
        'items', JSON_ARRAY(
          JSON_OBJECT('name', 'Java / Spring Boot', 'dim', false),
          JSON_OBJECT('name', 'MySQL / PostgreSQL', 'dim', false),
          JSON_OBJECT('name', 'Redis / Kafka', 'dim', false),
          JSON_OBJECT('name', 'Docker / Kubernetes', 'dim', false),
          JSON_OBJECT('name', 'Linux / Nginx', 'dim', false),
          JSON_OBJECT('name', 'Git / CI/CD', 'dim', false)
        )
      ),
      JSON_OBJECT(
        'label', '会用但不够熟',
        'items', JSON_ARRAY(
          JSON_OBJECT('name', 'Vue / Nuxt', 'dim', false),
          JSON_OBJECT('name', 'TypeScript', 'dim', false),
          JSON_OBJECT('name', 'Go / Rust（学习中）', 'dim', false),
          JSON_OBJECT('name', 'Elasticsearch', 'dim', false),
          JSON_OBJECT('name', 'React', 'dim', true),
          JSON_OBJECT('name', 'Swift / iOS 开发', 'dim', true)
        )
      )
    )
  ))
ON DUPLICATE KEY UPDATE `section` = `section`;

-- experience：经历时间线（从新到旧）
INSERT INTO `site_settings` (`section`, `data`) VALUES
  ('experience', JSON_OBJECT(
    'items', JSON_ARRAY(
      JSON_OBJECT(
        'time', '2022 — 现在',
        'title', '某互联网公司 · 高级后端工程师',
        'desc', '负责核心交易链路，从单体应用到逐步服务化。带过 3 人小组。'
      ),
      JSON_OBJECT(
        'time', '2018 — 2022',
        'title', '某 SaaS 公司 · 后端工程师',
        'desc', '从 0 到 1 参与了多租户 SaaS 平台的搭建，深入理解了权限、计费、数据隔离。'
      ),
      JSON_OBJECT(
        'time', '2016 — 2018',
        'title', '某电商公司 · Java 开发',
        'desc', '写了两年的 CRUD，第一次体会到「能跑起来」和「能扛住流量」之间有巨大的鸿沟。'
      )
    )
  ))
ON DUPLICATE KEY UPDATE `section` = `section`;

-- 公开读端点已被 /api/v1/public/settings 前缀覆盖（见 migration-site-settings.sql），
-- techstack / experience 走同一前缀，无需新增 api_whitelist 记录。
