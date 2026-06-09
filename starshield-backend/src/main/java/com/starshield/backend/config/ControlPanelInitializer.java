package com.starshield.backend.config;

import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import com.starshield.backend.service.ControlPanelService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时将默认敏感词与 Prompt 写入 Redis（仅在 key 缺失时）。
 */
@Component
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.WORKER, RuntimeMode.API})
public class ControlPanelInitializer implements ApplicationRunner {

    private final ControlPanelService controlPanelService;

    public ControlPanelInitializer(ControlPanelService controlPanelService) {
        this.controlPanelService = controlPanelService;
    }

    @Override
    public void run(ApplicationArguments args) {
        controlPanelService.ensureDefaultsSeeded();
    }
}
