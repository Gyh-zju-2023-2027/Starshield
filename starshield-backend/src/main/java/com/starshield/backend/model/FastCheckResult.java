package com.starshield.backend.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 引擎 A（高速规则）判定结果。
 */
@Data
@Accessors(chain = true)
public class FastCheckResult {

    private String decision;

    private Integer riskScore;

    private String labels;

    private String hitWords;

    private String reason;
}
