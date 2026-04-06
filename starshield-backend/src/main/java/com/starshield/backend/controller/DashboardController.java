package com.starshield.backend.controller;

import com.starshield.backend.common.Result;
import com.starshield.backend.service.DashboardControllerSupport;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 大屏实时指标接口。
 */
@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    private final DashboardControllerSupport dashboardControllerSupport;

    public DashboardController(DashboardControllerSupport dashboardControllerSupport) {
        this.dashboardControllerSupport = dashboardControllerSupport;
    }

    /**
     * 拉取全局指标。
     *
     * @author AI (under P9 supervision)
     */
    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        return dashboardControllerSupport.metrics();
    }
}
