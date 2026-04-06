package com.starshield.backend.service;

import com.starshield.backend.model.AiModerationResult;
import com.starshield.backend.model.ModerationDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 大模型分析服务。
 */
@Service
public class AiAnalysisService {

    @Value("${starshield.ai.provider:mock}")
    private String provider;

    @Value("${starshield.ai.prompt-version:v1}")
    private String promptVersion;

    /**
     * 对文本执行深度分析。
     *
     * @author AI (under P3 supervision)
     */
    public AiModerationResult analyze(String content) {
        String text = content == null ? "" : content;
        String lower = text.toLowerCase();

        AiModerationResult result = new AiModerationResult()
                .setProvider(provider)
                .setPromptVersion(promptVersion)
                .setConfidence(0.92d)
                .setLabels("normal")
                .setDecision(ModerationDecision.PASS)
                .setRiskScore(15)
                .setReason("语义正常");

        if (lower.contains("滚") || lower.contains("废物") || lower.contains("去死")) {
            result.setLabels("insult")
                    .setDecision(ModerationDecision.BLOCK)
                    .setRiskScore(92)
                    .setConfidence(0.97d)
                    .setReason("命中强攻击语义");
        } else if (lower.contains("加v") || lower.contains("私聊") || lower.contains("扫码")) {
            result.setLabels("traffic_diversion")
                    .setDecision(ModerationDecision.REVIEW)
                    .setRiskScore(76)
                    .setConfidence(0.90d)
                    .setReason("疑似引流语义");
        } else if (lower.contains("阴阳") || lower.contains("呵呵") || lower.contains("真厉害啊")) {
            result.setLabels("sarcasm")
                    .setDecision(ModerationDecision.REVIEW)
                    .setRiskScore(68)
                    .setConfidence(0.84d)
                    .setReason("疑似阴阳怪气/负面情绪");
        }

        return result;
    }
}
