-- =============================================
-- 20260607_device_whitelist.sql
-- 设备白名单：管理界面只允许已授权设备登录
-- =============================================
USE `blog`;

DROP TABLE IF EXISTS `admin_device`;
CREATE TABLE `admin_device` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`     VARCHAR(64)  NOT NULL                COMMENT '前端生成的设备 UUID（持久化在 localStorage）',
  `device_name`   VARCHAR(100) NOT NULL DEFAULT ''     COMMENT '设备名（从 UA 解析：Safari on macOS）',
  `user_agent`    VARCHAR(500) NOT NULL DEFAULT ''     COMMENT '原始 User-Agent',
  `ip`            VARCHAR(45)  NOT NULL DEFAULT ''     COMMENT '登录 IP',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending-待授权 / approved-已授权 / revoked-已吊销',
  `last_seen_at`  DATETIME     NULL                    COMMENT '最后活跃时间',
  `approved_at`   DATETIME     NULL                    COMMENT '授权时间',
  `approved_by`   VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '授权来源 device_id（系统自动迁移时为 system）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_id` (`device_id`),
  KEY `idx_status` (`status`),
  KEY `idx_last_seen` (`last_seen_at`)
) ENGINE = InnoDB COMMENT ='管理后台设备白名单';

-- 验证：当前表为空
-- 旧客户端（不传 device_id）会走 trust-migrate 通道，登录成功后自动写一条 approved 记录
-- 之后所有新设备都受白名单管控
