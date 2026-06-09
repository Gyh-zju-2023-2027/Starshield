package com.starshield.backend.service;

import com.starshield.backend.config.RabbitMQConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 业务链路指标埋点，供 Prometheus/Grafana 观测接入、MQ 与消费延迟。
 */
@Service
public class StarshieldMetrics {

    public static final String ENQUEUED_AT_HEADER = "x-starshield-enqueued-at";

    private final MeterRegistry meterRegistry;

    public StarshieldMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordIngestRequest(String outcome) {
        meterRegistry.counter("starshield.ingest.requests", "outcome", safeTag(outcome)).increment();
    }

    public void recordRateLimited(String scope) {
        meterRegistry.counter("starshield.ingest.rate_limited", "scope", safeTag(scope)).increment();
        recordIngestRequest("rate_limited");
    }

    public void recordMqPublished() {
        meterRegistry.counter("starshield.mq.published", "queue", RabbitMQConfig.CHAT_MESSAGE_QUEUE).increment();
    }

    public void recordMqPublishError() {
        meterRegistry.counter("starshield.mq.publish.errors", "queue", RabbitMQConfig.CHAT_MESSAGE_QUEUE).increment();
    }

    public Timer.Sample startConsumerTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordConsumerMessage(String outcome) {
        meterRegistry.counter(
                "starshield.mq.consumer.messages",
                "queue", RabbitMQConfig.CHAT_MESSAGE_QUEUE,
                "outcome", safeTag(outcome)
        ).increment();
    }

    public void recordConsumerProcessing(Timer.Sample sample, String outcome) {
        sample.stop(meterRegistry.timer(
                "starshield.mq.consumer.processing",
                "queue", RabbitMQConfig.CHAT_MESSAGE_QUEUE,
                "outcome", safeTag(outcome)
        ));
    }

    public void recordConsumerLag(Object enqueuedAtHeader) {
        Long enqueuedAt = parseMillis(enqueuedAtHeader);
        if (enqueuedAt == null) {
            return;
        }
        long lagMs = Math.max(0L, System.currentTimeMillis() - enqueuedAt);
        meterRegistry.timer(
                "starshield.mq.consumer.lag",
                "queue", RabbitMQConfig.CHAT_MESSAGE_QUEUE
        ).record(Duration.ofMillis(lagMs));
    }

    private Long parseMillis(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
