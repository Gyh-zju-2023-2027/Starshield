package com.starshield.backend.config.runtime;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;
import java.util.Map;

public class OnModeCondition implements Condition {

    private static final String MODE_KEY = "starshield.runtime.mode";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnabledOnMode.class.getName());
        if (attrs == null) {
            return false;
        }
        RuntimeMode[] modes = (RuntimeMode[]) attrs.get("value");
        RuntimeMode current = parseMode(context.getEnvironment().getProperty(MODE_KEY, "monolith"));
        return Arrays.asList(modes).contains(current);
    }

    static RuntimeMode parseMode(String raw) {
        try {
            return RuntimeMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return RuntimeMode.MONOLITH;
        }
    }
}
