package com.starshield.backend.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * RabbitMQ 队列深度指标，暴露给 /actuator/prometheus。
 */
@Configuration
public class RabbitMqMetricsConfig {

    @Bean
    @ConditionalOnMissingBean(RabbitAdmin.class)
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public MeterBinder starshieldRabbitQueueMetrics(RabbitAdmin rabbitAdmin) {
        return registry -> {
            registerQueueGauges(registry, rabbitAdmin, RabbitMQConfig.CHAT_MESSAGE_QUEUE);
            registerQueueGauges(registry, rabbitAdmin, RabbitMQConfig.CHAT_MESSAGE_DLQ);
        };
    }

    private void registerQueueGauges(io.micrometer.core.instrument.MeterRegistry registry,
                                     RabbitAdmin rabbitAdmin,
                                     String queue) {
        Gauge.builder("starshield.mq.queue.ready", () -> queueProperty(rabbitAdmin, queue, RabbitAdmin.QUEUE_MESSAGE_COUNT))
                .description("RabbitMQ ready message count")
                .tag("queue", queue)
                .register(registry);
        Gauge.builder("starshield.mq.queue.consumers", () -> queueProperty(rabbitAdmin, queue, RabbitAdmin.QUEUE_CONSUMER_COUNT))
                .description("RabbitMQ consumer count")
                .tag("queue", queue)
                .register(registry);
    }

    private double queueProperty(RabbitAdmin rabbitAdmin, String queue, Object property) {
        try {
            Properties properties = rabbitAdmin.getQueueProperties(queue);
            if (properties == null) {
                return 0.0d;
            }
            Object value = properties.get(property);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Double.parseDouble(text);
            }
        } catch (Exception ignored) {
            return Double.NaN;
        }
        return 0.0d;
    }
}
