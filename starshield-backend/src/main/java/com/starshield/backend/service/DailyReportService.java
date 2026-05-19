package com.starshield.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.entity.DailyReportCache;
import com.starshield.backend.mapper.ChatMessageLogMapper;
import com.starshield.backend.mapper.DailyReportCacheMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);

    private final ChatMessageLogMapper chatMessageLogMapper;
    private final DailyReportCacheMapper dailyReportCacheMapper;
    private final AiAnalysisService aiAnalysisService;
    private final ObjectMapper objectMapper;

    public DailyReportService(ChatMessageLogMapper chatMessageLogMapper,
                              DailyReportCacheMapper dailyReportCacheMapper,
                              AiAnalysisService aiAnalysisService,
                              ObjectMapper objectMapper) {
        this.chatMessageLogMapper = chatMessageLogMapper;
        this.dailyReportCacheMapper = dailyReportCacheMapper;
        this.aiAnalysisService = aiAnalysisService;
        this.objectMapper = objectMapper;
    }

    /**
     * 每天凌晨 2 点执行前一天的报告生成
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void generateYesterdayReport() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("[每日战报] 开始生成昨日 ({}) 的每日战报", yesterday);
        try {
            buildAndSaveReport(yesterday);
            log.info("[每日战报] 成功生成昨日 ({}) 的每日战报", yesterday);
        } catch (Exception e) {
            log.error("[每日战报] 生成报表失败", e);
        }
    }

    public String getOrBuildReport(LocalDate date) throws Exception {
        // 先查缓存
        DailyReportCache cache = dailyReportCacheMapper.selectById(date);
        if (cache != null) {
            log.info("[每日战报] 命中 {} 的缓存战报", date);
            return cache.getPayloadJson();
        }

        // 不存在则构建
        log.info("[每日战报] 未命中 {} 的缓存战报，执行即时(On-the-fly)构建", date);
        Map<String, Object> payload = buildPayload(date);
        String payloadJson = objectMapper.writeValueAsString(payload);

        // 如果是历史日期，或者到了第二天，则落库保存
        if (date.isBefore(LocalDate.now())) {
            DailyReportCache newCache = new DailyReportCache();
            newCache.setDate(date);
            newCache.setPayloadJson(payloadJson);
            newCache.setCreateTime(LocalDateTime.now());
            dailyReportCacheMapper.insert(newCache);
        }

        return payloadJson;
    }

    private void buildAndSaveReport(LocalDate date) throws Exception {
        Map<String, Object> payload = buildPayload(date);
        String payloadJson = objectMapper.writeValueAsString(payload);
        
        DailyReportCache cache = new DailyReportCache();
        cache.setDate(date);
        cache.setPayloadJson(payloadJson);
        cache.setCreateTime(LocalDateTime.now());
        
        dailyReportCacheMapper.insert(cache);
    }

    private Map<String, Object> buildPayload(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();

        QueryWrapper<ChatMessageLog> baseQuery = new QueryWrapper<>();
        baseQuery.ge("create_time", startOfDay).lt("create_time", endOfDay);

        // 1. 各项总量
        long totalCount = chatMessageLogMapper.selectCount(baseQuery);
        
        QueryWrapper<ChatMessageLog> blockQuery = new QueryWrapper<>();
        blockQuery.ge("create_time", startOfDay).lt("create_time", endOfDay).eq("decision", "BLOCK");
        long blockCount = chatMessageLogMapper.selectCount(blockQuery);
        
        QueryWrapper<ChatMessageLog> reviewQuery = new QueryWrapper<>();
        reviewQuery.ge("create_time", startOfDay).lt("create_time", endOfDay).eq("decision", "REVIEW");
        long reviewCount = chatMessageLogMapper.selectCount(reviewQuery);

        double violationRate = totalCount == 0 ? 0 : (double) blockCount / totalCount;

        // 2. 小时分布直方图数组（24个元素）
        List<Map<String, Object>> hourlyStats = chatMessageLogMapper.countHourlyBuckets(startOfDay, endOfDay);
        int[] hourlyBuckets = new int[24];
        if (hourlyStats != null) {
            for (Map<String, Object> stat : hourlyStats) {
                int hr = ((Number) stat.get("hr")).intValue();
                int cnt = ((Number) stat.get("cnt")).intValue();
                if (hr >= 0 && hr < 24) {
                    hourlyBuckets[hr] = cnt;
                }
            }
        }

        // 3. 典型案例 (最多返回 3 条 BLOCK 记录)
        QueryWrapper<ChatMessageLog> typicalQuery = new QueryWrapper<>();
        typicalQuery.ge("create_time", startOfDay).lt("create_time", endOfDay)
                .eq("decision", "BLOCK")
                .orderByDesc("risk_score")
                .last("LIMIT 3");
        List<ChatMessageLog> typicalCaseLogs = chatMessageLogMapper.selectList(typicalQuery);
        List<Map<String, Object>> typicalCases = new ArrayList<>();
        if (typicalCaseLogs != null) {
            for (ChatMessageLog cl : typicalCaseLogs) {
                typicalCases.add(Map.of(
                        "id", cl.getId(),
                        "content", cl.getContent() != null ? cl.getContent() : "",
                        "reasonTag", cl.getReasonTag() != null ? cl.getReasonTag() : "",
                        "score", cl.getRiskScore() != null ? cl.getRiskScore() : 0
                ));
            }
        }

        // 4. TOP 玩家违规
        List<Map<String, Object>> topPlayers = chatMessageLogMapper.countTopPlayers(startOfDay, endOfDay);
        if (topPlayers == null) topPlayers = new ArrayList<>();

        // 5. 命中词汇云数据 (通过应用层打散拆分)
        List<String> hitWordsList = chatMessageLogMapper.selectHitWords(startOfDay, endOfDay);
        Map<String, Integer> wordCountMap = new HashMap<>();
        if (hitWordsList != null) {
            for (String hw : hitWordsList) {
                if (hw != null && !hw.isBlank()) {
                    String[] words = hw.split(",");
                    for (String w : words) {
                        String clean = w.trim();
                        if (!clean.isEmpty()) {
                            wordCountMap.put(clean, wordCountMap.getOrDefault(clean, 0) + 1);
                        }
                    }
                }
            }
        }
        
        List<Map<String, Object>> topKeywords = wordCountMap.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(20)
                .map(e -> Map.of("word", e.getKey(), "count", (Object) e.getValue()))
                .collect(Collectors.toList());

        // 6. 构造初版数据供大模型阅读
        Map<String, Object> payload = new HashMap<>();
        payload.put("date", date.toString());
        payload.put("totalCount", totalCount);
        payload.put("blockCount", blockCount);
        payload.put("reviewCount", reviewCount);
        payload.put("violationRate", violationRate);
        payload.put("hourlyBuckets", hourlyBuckets);
        payload.put("topPlayers", topPlayers);
        payload.put("topKeywords", topKeywords);
        payload.put("typicalCases", typicalCases);

        // 7. 通知大模型生成纯文本总结
        try {
            String statsJsonForAI = objectMapper.writeValueAsString(payload);
            String aiSummary = aiAnalysisService.generateDailySummary(statsJsonForAI);
            payload.put("aiSummary", aiSummary);
        } catch (Exception e) {
            payload.put("aiSummary", "生成AI总结时发生异常数据转换错误。");
        }

        return payload;
    }
}
