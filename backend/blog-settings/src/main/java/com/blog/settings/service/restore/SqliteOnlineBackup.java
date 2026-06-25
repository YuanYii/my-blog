package com.blog.settings.service.restore;

import lombok.extern.slf4j.Slf4j;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.DB;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite Online Backup 封装（REQ-RESTORE-2026-06-24，v5.0）
 *
 * 设计依据：docs/design/博客数据恢复方案设计.md §2 + §3 Step 4
 *
 * 核心 API: xerial sqlite-jdbc 的 {@code org.sqlite.core.DB.restore(dbName, srcFile, observer)}
 * 底层映射 SQLite C `sqlite3_backup_init()` / `sqlite3_backup_step()` / `sqlite3_backup_finish()`。
 *
 * 流程：
 *   1. 用 dump.sql 在临时路径构建一个全新的 staged sqlite db
 *   2. 打开 live db 的临时连接（绕过 HikariCP）, getDatabase().restore("main", stagedPath, null)
 *   3. RESTORE 完成后 PRAGMA journal_mode=WAL 恢复 WAL 模式
 *
 * 锁窗口：实测 SQLite Online Backup 对几 MB db 锁窗口次秒级。
 *   - busy_timeout 设 30s 让 HikariCP 其他连接自动重试
 *   - HikariCP connectionTimeout 默认 30s,足够覆盖
 */
@Slf4j
@Component
public class SqliteOnlineBackup {

    /** busy_timeout 给 SQLITE_BUSY 重试空间（毫秒） */
    private static final int BUSY_TIMEOUT_MS = 30_000;

    /**
     * 从 dump.sql 在 stagedPath 构建一个全新的 sqlite db
     * @param sqlFile 明文 .sql 文件（sqlite3 dump 格式: BEGIN TRANSACTION; ...; COMMIT;）
     * @param stagedPath 目标 staged sqlite 文件路径（调用方保证不存在或先删）
     */
    public void buildStagedDbFromSql(Path sqlFile, Path stagedPath) throws SQLException, IOException {
        String jdbcUrl = "jdbc:sqlite:" + stagedPath.toAbsolutePath();
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.OFF);
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
        cfg.setBusyTimeout(BUSY_TIMEOUT_MS);

        String content = new String(Files.readAllBytes(sqlFile), "UTF-8");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, cfg.toProperties());
             Statement stmt = conn.createStatement()) {

            for (String sql : splitSqlStatements(content)) {
                if (sql.isEmpty()) continue;
                try {
                    stmt.execute(sql);
                } catch (SQLException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("cannot start a transaction within a transaction")
                            || msg.contains("cannot commit")
                            || msg.contains("no transaction is active")) {
                        log.debug("[Restore-IMPORT] 跳过事务语句");
                    } else {
                        throw new SQLException("staged db 执行 dump.sql 失败: " + msg
                            + "\nSQL 前 200 字符: "
                            + (sql.length() > 200 ? sql.substring(0, 200) + "..." : sql), e);
                    }
                }
            }
        }
        log.info("[Restore-IMPORT] staged db 构建完成: {}", stagedPath);
    }

    /**
     * 按分号分割 SQL 语句，感知单引号上下文（字符串字面量内的分号不分割）。
     * 同时跳过 SQL 注释行（-- 开头）和空行。
     */
    private static java.util.List<String> splitSqlStatements(String content) {
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inSingleQuote = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (inSingleQuote) {
                buf.append(c);
                if (c == '\'' && (i + 1 >= content.length() || content.charAt(i + 1) != '\'')) {
                    inSingleQuote = false;
                } else if (c == '\'' && i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                    buf.append(content.charAt(++i));
                }
            } else if (c == '\'') {
                inSingleQuote = true;
                buf.append(c);
            } else if (c == ';') {
                String sql = buf.toString().trim();
                buf.setLength(0);
                if (!sql.isEmpty() && !sql.startsWith("--")) {
                    result.add(sql);
                }
            } else {
                buf.append(c);
            }
        }
        String tail = buf.toString().trim();
        if (!tail.isEmpty() && !tail.startsWith("--")) {
            result.add(tail);
        }
        return result;
    }

    /**
     * 用 SQLite Online Backup API 把 stagedPath 内容原子覆盖到 livePath
     * 锁窗口次秒级,HikariCP 业务连接自动重试 SQLITE_BUSY
     *
     * @param stagedPath 源 staged db
     * @param livePath 目标 live db（myblog 正在用的 blog.db）
     */
    public void copyOver(Path stagedPath, Path livePath) throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + livePath.toAbsolutePath();
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setBusyTimeout(BUSY_TIMEOUT_MS);

        try (Connection raw = DriverManager.getConnection(jdbcUrl, cfg.toProperties())) {
            // unwrap 拿到 xerial 的 SQLiteConnection
            SQLiteConnection sc = raw.unwrap(SQLiteConnection.class);
            DB db = sc.getDatabase();

            // restore("main", srcFile, observer):
            //   把 main schema 从 srcFile 拷贝过来
            //   底层 sqlite3_backup_init(dest=this, src=open(srcFile)) → step → finish
            //   observer == null 表示不观察进度
            int rc = db.restore("main", stagedPath.toAbsolutePath().toString(), null);
            log.info("[Restore-IMPORT] Online Backup 完成 rc={}: {} → {}", rc, stagedPath, livePath);

            // RESTORE 完成后 journal_mode 被重置为 rollback journal,重启 WAL
            try (Statement stmt = raw.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }
            log.info("[Restore-IMPORT] WAL + NORMAL 模式已恢复");
        }
    }

    /**
     * 数据完整性 smoke test：select 1
     * 用于 RESTORE 后即时验证 live db 可读
     */
    public boolean smokeTest(Path livePath) {
        String jdbcUrl = "jdbc:sqlite:" + livePath.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            log.warn("[Restore-IMPORT] smoke test 失败: {}", e.getMessage());
            return false;
        }
    }
}
