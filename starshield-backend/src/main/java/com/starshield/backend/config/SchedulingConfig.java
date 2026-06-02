package com.starshield.backend.config;

import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class SchedulingConfig {
}
