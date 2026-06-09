package com.starshield.backend.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理接口 API Key 鉴权配置。
 */
@ConfigurationProperties(prefix = "starshield.security")
public class AdminAuthProperties {

    /**
     * 管理令牌；为空时关闭鉴权（仅建议本地调试）。
     */
    private String adminApiKey = "starshield-dev-admin-key";

    public String getAdminApiKey() {
        return adminApiKey;
    }

    public void setAdminApiKey(String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    public boolean isEnabled() {
        return adminApiKey != null && !adminApiKey.isBlank();
    }
}
