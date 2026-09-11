package com.example.camundademo.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// RabbitMQ messaging infrastructure configuration, active when metaml.messaging.enabled=true.
@Configuration
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class MessagingConfig {

    // Uses Jackson JSON converter for AMQP message payloads.
    @Bean
    public MessageConverter harnessMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // Flow A: Manufacturing and machines messaging definitions.

    @Bean
    public DirectExchange machinesExchange() {
        return new DirectExchange(MessagingTopology.MACHINES_EXCHANGE);
    }

    @Bean
    public Queue machinesRequestQueue() {
        return new Queue(MessagingTopology.MACHINES_REQUEST_QUEUE);
    }

    @Bean
    public Queue machinesCompletionQueue() {
        return new Queue(MessagingTopology.MACHINES_COMPLETION_QUEUE);
    }

    @Bean
    public Binding machinesRequestBinding() {
        return BindingBuilder.bind(machinesRequestQueue()).to(machinesExchange())
                .with(MessagingTopology.MACHINES_REQUEST_KEY);
    }

    @Bean
    public Binding machinesCompletionBinding() {
        return BindingBuilder.bind(machinesCompletionQueue()).to(machinesExchange())
                .with(MessagingTopology.MACHINES_COMPLETION_KEY);
    }

    // Flow B: Manufacturing and twin messaging definitions.

    @Bean
    public DirectExchange twinExchange() {
        return new DirectExchange(MessagingTopology.TWIN_EXCHANGE);
    }

    @Bean
    public Queue twinStageUpdateQueue() {
        return new Queue(MessagingTopology.TWIN_STAGE_UPDATE_QUEUE);
    }

    @Bean
    public Queue twinStageResponseQueue() {
        return new Queue(MessagingTopology.TWIN_STAGE_RESPONSE_QUEUE);
    }

    @Bean
    public Binding twinStageUpdateBinding() {
        return BindingBuilder.bind(twinStageUpdateQueue()).to(twinExchange())
                .with(MessagingTopology.TWIN_STAGE_UPDATE_KEY);
    }

    @Bean
    public Binding twinStageResponseBinding() {
        return BindingBuilder.bind(twinStageResponseQueue()).to(twinExchange())
                .with(MessagingTopology.TWIN_STAGE_RESPONSE_KEY);
    }

    // Flow C: Twin and gateway messaging definitions.

    @Bean
    public DirectExchange gatewayExchange() {
        return new DirectExchange(MessagingTopology.GATEWAY_EXCHANGE);
    }

    @Bean
    public Queue gatewayQcRequestQueue() {
        return new Queue(MessagingTopology.GATEWAY_QC_REQUEST_QUEUE);
    }

    @Bean
    public Queue gatewayQcResponseQueue() {
        return new Queue(MessagingTopology.GATEWAY_QC_RESPONSE_QUEUE);
    }

    @Bean
    public Binding gatewayQcRequestBinding() {
        return BindingBuilder.bind(gatewayQcRequestQueue()).to(gatewayExchange())
                .with(MessagingTopology.GATEWAY_QC_REQUEST_KEY);
    }

    @Bean
    public Binding gatewayQcResponseBinding() {
        return BindingBuilder.bind(gatewayQcResponseQueue()).to(gatewayExchange())
                .with(MessagingTopology.GATEWAY_QC_RESPONSE_KEY);
    }
}
