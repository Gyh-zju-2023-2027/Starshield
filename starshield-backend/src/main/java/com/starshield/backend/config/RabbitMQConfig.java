package com.starshield.backend.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 核心配置类
 * <p>
 * 【架构设计说明】
 * 采用「交换机 + 路由键 + 队列」的标准模式，而非直接使用默认交换机。
 * 好处：
 *   1. 解耦：生产者只关心交换机，不关心具体队列，便于后续扩展（如增加死信队列）
 *   2. 灵活路由：通过路由键可将同一条消息分发到多个消费者队列
 *   3. 可观测：队列、交换机、绑定关系清晰，运维友好
 */
@Configuration
public class RabbitMQConfig {

    // ===================== 常量定义 =====================

    /** 主消息队列名称 */
    public static final String CHAT_MESSAGE_QUEUE = "chat.message.queue";

    /** 死信队列（用于处理消费失败的消息，防止消息丢失） */
    public static final String CHAT_MESSAGE_DLQ = "chat.message.dlq";

    /** 直连交换机名称 */
    public static final String CHAT_EXCHANGE = "chat.direct.exchange";

    /** 死信交换机 */
    public static final String CHAT_DL_EXCHANGE = "chat.dl.exchange";

    /** 路由键 */
    public static final String CHAT_ROUTING_KEY = "chat.message.routing.key";

    /** 死信路由键 */
    public static final String CHAT_DL_ROUTING_KEY = "chat.message.dl.routing.key";

    // ===================== 交换机声明 =====================

    /**
     * 声明直连交换机（主）
     * durable = true：RabbitMQ 重启后交换机依然存在
     */
    @Bean
    public DirectExchange chatDirectExchange() {
        return ExchangeBuilder
                .directExchange(CHAT_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 声明死信交换机
     */
    @Bean
    public DirectExchange chatDeadLetterExchange() {
        return ExchangeBuilder
                .directExchange(CHAT_DL_EXCHANGE)
                .durable(true)
                .build();
    }

    // ===================== 队列声明 =====================

    /**
     * 声明主消息队列
     * durable = true：持久化，RabbitMQ 重启后队列和消息不丢失
     * 绑定死信交换机：当消息消费失败超过重试次数后，转入死信队列
     */
    @Bean
    public Queue chatMessageQueue() {
        return QueueBuilder
                .durable(CHAT_MESSAGE_QUEUE)
                // 配置死信交换机，消费失败的消息自动转发到此
                .withArgument("x-dead-letter-exchange", CHAT_DL_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CHAT_DL_ROUTING_KEY)
                .build();
    }

    /**
     * 声明死信队列
     * 用于接收消费失败的消息，后续可人工处理或告警
     */
    @Bean
    public Queue chatMessageDLQ() {
        return QueueBuilder
                .durable(CHAT_MESSAGE_DLQ)
                .build();
    }

    // ===================== 绑定关系 =====================

    /**
     * 绑定主队列到主交换机
     */
    @Bean
    public Binding chatMessageBinding(Queue chatMessageQueue, DirectExchange chatDirectExchange) {
        return BindingBuilder
                .bind(chatMessageQueue)
                .to(chatDirectExchange)
                .with(CHAT_ROUTING_KEY);
    }

    /**
     * 绑定死信队列到死信交换机
     */
    @Bean
    public Binding chatDLQBinding(Queue chatMessageDLQ, DirectExchange chatDeadLetterExchange) {
        return BindingBuilder
                .bind(chatMessageDLQ)
                .to(chatDeadLetterExchange)
                .with(CHAT_DL_ROUTING_KEY);
    }

    // ===================== 消息转换器 =====================

    /**
     * 使用 Jackson 将消息对象序列化为 JSON
     * 替换默认的 Java 序列化方式，提升可读性和跨语言兼容性
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置 RabbitTemplate，注入 JSON 消息转换器
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        // 开启发送确认（confirm callback），生产环境中可配合 ConfirmCallback 保障可靠发送
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                // 实际生产中应记录日志并触发告警或重试
                System.err.println("[RabbitMQ] 消息发送失败! cause: " + cause);
            }
        });
        return template;
    }
}
