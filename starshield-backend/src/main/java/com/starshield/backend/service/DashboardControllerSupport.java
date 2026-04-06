package com.starshield.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshield.backend.common.Result;
import com.starshield.backend.entity.ChatMessageLog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 大屏指标聚合支持服务。
 */
@Service
public class DashboardControllerSupport {

    private final ChatMessageService chatMessageService;

    public DashboardControllerSupport(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    /**
     * 构建实时指标结果。
     *
     * @author AI (under P9 supervision)
     */
    public Result<Map<String, Object>> metrics() {
        long total = chatMessageService.count();
        long blocked = chatMessageService.count(new LambdaQueryWrapper<ChatMessageLog>()
                .eq(ChatMessageLog::getDecision, "BLOCK"));
        long review = chatMessageService.count(new LambdaQueryWrapper<ChatMessageLog>()
                .eq(ChatMessageLog::getDecision, "REVIEW"));

        double blockRate = total == 0 ? 0d : (blocked * 100.0 / total);

        List<ChatMessageLog> latest = chatMessageService.list(new LambdaQueryWrapper<ChatMessageLog>()
                .orderByDesc(ChatMessageLog::getCreateTime)
                .last("limit 100"));

        return Result.success(Map.of(
                "total", total,
                "blocked", blocked,
                "review", review,
                "blockRate", blockRate,
                "latest", latest
        ));
    }
}
