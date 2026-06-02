package com.starshield.backend.config;

import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.starshield.backend.mapper")
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.WORKER, RuntimeMode.API})
public class MyBatisConfig {
}
