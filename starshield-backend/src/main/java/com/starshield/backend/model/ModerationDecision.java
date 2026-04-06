package com.starshield.backend.model;

/**
 * 审核决策枚举常量。
 */
public final class ModerationDecision {

    private ModerationDecision() {
    }

    public static final String PASS = "PASS";
    public static final String BLOCK = "BLOCK";
    public static final String REVIEW = "REVIEW";
}
