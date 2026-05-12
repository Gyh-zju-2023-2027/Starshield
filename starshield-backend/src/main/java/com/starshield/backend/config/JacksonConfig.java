package com.starshield.backend.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 让 Long / BigInteger 在 JSON 中以字符串形式输出，
 * 避免雪花 ID（19 位）传到前端 JS 时丢精度（JS Number 仅安全到 2^53）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            module.addSerializer(java.math.BigInteger.class, ToStringSerializer.instance);
            // 用 modulesToInstall（追加）而不是 modules（替换），
            // 否则会覆盖 Spring Boot 默认装的 jsr310 模块，导致 LocalDateTime 序列化失败。
            builder.modulesToInstall(module);
        };
    }
}
