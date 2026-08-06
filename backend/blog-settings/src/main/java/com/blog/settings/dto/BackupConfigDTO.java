package com.blog.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 备份仓库配置 DTO (20260806-DEV-001)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupConfigDTO {
    /** 备份仓库地址 (e.g. owner/my-blog-backup) */
    private String repo;

    /** 备份 GitHub Token */
    private String token;

    /** 自定义加密码 */
    private String encryptionPassword;

    /** Token 是否已配置 */
    private Boolean isTokenSet;

    /** 加密码是否已配置 */
    private Boolean isPasswordSet;

    /** 掩码后的 Token */
    private String maskedToken;

    /** 掩码后的加密码 */
    private String maskedPassword;
}
