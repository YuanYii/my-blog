package com.blog.settings.service.restore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.zip.GZIPInputStream;

/**
 * 加密 dump 解码器（REQ-RESTORE-2026-06-24，v5.0）
 *
 * 设计依据：docs/design/博客数据恢复方案设计.md §3 Step 3
 *
 * openssl enc -aes-256-cbc -pbkdf2 -iter 10 -salt 的兼容实现：
 *  - 文件格式: Salted__{8 字节 salt}{加密数据}
 *  - KDF: PBKDF2WithHmacSHA256 (openssl 1.1+ 默认；可被 -md 改写,本项目未改)
 *  - key: 32 字节 (AES-256), iv: 16 字节, 由 PBKDF2 推 48 字节然后切
 *  - 解密: AES/CBC/PKCS5Padding
 *
 * 用法（DB dump）：
 *   decryptAndDecompress(encFile, password, sqlFile)
 *     enc → 解密 → gunzip → 明文 .sql
 *
 * 用法（uploads tar.gz）：
 *   decrypt(encFile, password, tarGzFile)
 *     enc → 解密 → tar.gz（不 gunzip,交给 commons-compress 流式解压）
 *
 * 替代品：旧版调 openssl + gunzip 子进程，新版纯 Java 无外部依赖
 */
@Slf4j
@Component
public class EncryptedDumpCodec {

    /** openssl 加密文件头部 magic */
    private static final byte[] OPENSSL_MAGIC = "Salted__".getBytes();
    private static final int SALT_LEN = 8;
    private static final int KEY_LEN = 32;   // AES-256
    private static final int IV_LEN = 16;    // AES block size
    private static final int PBKDF2_ITERATIONS = 10;  // 与备份脚本对齐
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_TRANSFORM = "AES/CBC/PKCS5Padding";

    /**
     * 解密 + gunzip：用于 db dump 文件
     * @param encFile *.sql.gz.enc
     * @param password BACKUP_ENCRYPTION_PASSWORD
     * @param sqlFile 输出的明文 .sql 文件
     */
    public void decryptAndDecompress(Path encFile, String password, Path sqlFile)
            throws IOException, GeneralSecurityException {
        try (InputStream in = openDecryptStream(encFile, password);
             GZIPInputStream gz = new GZIPInputStream(in);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(sqlFile.toFile()))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        log.info("[Restore-DECRYPT] {} → {} ({} bytes)", encFile.getFileName(),
            sqlFile.getFileName(), Files.size(sqlFile));
    }

    /**
     * 仅解密（不 gunzip）：用于 uploads 的 tar.gz
     * @param encFile *.tar.gz.enc
     * @param password BACKUP_ENCRYPTION_PASSWORD
     * @param tarGzFile 输出的 .tar.gz 文件（仍是压缩的,交给 commons-compress 流式解包）
     */
    public void decrypt(Path encFile, String password, Path tarGzFile)
            throws IOException, GeneralSecurityException {
        try (InputStream in = openDecryptStream(encFile, password);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tarGzFile.toFile()))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        log.info("[Restore-DECRYPT] {} → {} ({} bytes)", encFile.getFileName(),
            tarGzFile.getFileName(), Files.size(tarGzFile));
    }

    /**
     * 打开解密流：读 openssl 头部 → PBKDF2 推 key/iv → Cipher 包装 InputStream
     * 返回的 InputStream 仍是明文（如有 gzip 包装由调用方处理）
     */
    private InputStream openDecryptStream(Path encFile, String password)
            throws IOException, GeneralSecurityException {
        InputStream raw = new BufferedInputStream(new FileInputStream(encFile.toFile()));

        // 1. 读 magic + salt（16 字节）
        byte[] header = new byte[OPENSSL_MAGIC.length + SALT_LEN];
        int read = readFully(raw, header, 0, header.length);
        if (read != header.length) {
            raw.close();
            throw new IOException("加密文件过短,无法读取 openssl header: " + encFile);
        }
        for (int i = 0; i < OPENSSL_MAGIC.length; i++) {
            if (header[i] != OPENSSL_MAGIC[i]) {
                raw.close();
                throw new IOException("加密文件 magic 不匹配 (期望 'Salted__'): " + encFile);
            }
        }
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(header, OPENSSL_MAGIC.length, salt, 0, SALT_LEN);

        // 2. PBKDF2 推 48 字节 → 切 key (32) + iv (16)
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        PBEKeySpec spec = new PBEKeySpec(
            password.toCharArray(), salt, PBKDF2_ITERATIONS, (KEY_LEN + IV_LEN) * 8);
        byte[] keyAndIv = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();

        byte[] key = new byte[KEY_LEN];
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(keyAndIv, 0, key, 0, KEY_LEN);
        System.arraycopy(keyAndIv, KEY_LEN, iv, 0, IV_LEN);

        // 3. 构建解密 Cipher
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new IvParameterSpec(iv));

        // 4. 返回 CipherInputStream（包装 raw,关闭时会关 raw）
        return new javax.crypto.CipherInputStream(raw, cipher);
    }

    /** 读满指定长度（处理短读） */
    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }
}
