package com.starshield.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.common.Result;
import com.starshield.backend.service.DailyReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 战报和核心报表接口
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final DailyReportService dailyReportService;
    private final ObjectMapper objectMapper;

    public ReportController(DailyReportService dailyReportService, ObjectMapper objectMapper) {
        this.dailyReportService = dailyReportService;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取指定日期的 AI 治理战报
     * 示例：GET /api/reports/daily?date=2026-05-18
     */
    @GetMapping("/daily")
    public Result<JsonNode> getDailyReport(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            // 获取 JSON 原文
            String payloadJson = dailyReportService.getOrBuildReport(date);
            // 转换为 JsonNode，以便 Spring Web 序列化为规范 JSON 而非转义字符串
            JsonNode data = objectMapper.readTree(payloadJson);
            return Result.success(data);
        } catch (Exception e) {
            return Result.error(500, "获/每日战报失败：" + e.getMessage());
        }
    }
}
