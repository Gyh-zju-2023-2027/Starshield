package com.starshield.backend.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 引擎 B（深度 AI）判定结果。
 */
@Data
@Accessors(chain = true)
public class AiModerationResult {

    private int riskScore;

    private String labels;

    private String decision;

    private Double confidence;

    private String reason;

    private String provider;

    private String promptVersion;

    private String modelTier;

    private boolean degraded;
}
