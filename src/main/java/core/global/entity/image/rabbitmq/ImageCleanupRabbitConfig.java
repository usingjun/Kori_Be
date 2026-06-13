package core.global.entity.image.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static core.global.entity.image.rabbitmq.ImageCleanupRabbitNames.*;

@Configuration
@ConditionalOnProperty(name = "image.cleanup.rabbit.enabled", havingValue = "true")
public class ImageCleanupRabbitConfig {

    @Bean
    public TopicExchange imageOperationExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange imageOperationRetryExchange() {
        return new TopicExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange imageOperationDlx() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue imageOperationQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Queue imageOperationRetry1mQueue() {
        return retryQueue(RETRY_1M_QUEUE, 60_000);
    }

    @Bean
    public Queue imageOperationRetry5mQueue() {
        return retryQueue(RETRY_5M_QUEUE, 300_000);
    }

    @Bean
    public Queue imageOperationRetry15mQueue() {
        return retryQueue(RETRY_15M_QUEUE, 900_000);
    }

    @Bean
    public Queue imageOperationRetry1hQueue() {
        return retryQueue(RETRY_1H_QUEUE, 3_600_000);
    }

    @Bean
    public Queue imageOperationDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Queue imageOperationStepQueue() {
        return QueueBuilder.durable(ImageOperationRabbitNames.STEP_QUEUE).build();
    }

    @Bean
    public Queue imageOperationStepRetry1mQueue() {
        return retryQueue(ImageOperationRabbitNames.STEP_RETRY_1M_QUEUE, 60_000);
    }

    @Bean
    public Queue imageOperationStepRetry5mQueue() {
        return retryQueue(ImageOperationRabbitNames.STEP_RETRY_5M_QUEUE, 300_000);
    }

    @Bean
    public Queue imageOperationStepRetry15mQueue() {
        return retryQueue(ImageOperationRabbitNames.STEP_RETRY_15M_QUEUE, 900_000);
    }

    @Bean
    public Queue imageOperationStepRetry1hQueue() {
        return retryQueue(ImageOperationRabbitNames.STEP_RETRY_1H_QUEUE, 3_600_000);
    }

    @Bean
    public Queue imageOperationStepDlq() {
        return QueueBuilder.durable(ImageOperationRabbitNames.STEP_DLQ).build();
    }

    @Bean
    public Binding imageCleanupBinding() {
        return BindingBuilder.bind(imageOperationQueue()).to(imageOperationExchange()).with("image.cleanup.*");
    }

    @Bean
    public Binding imageRetryReturnBinding() {
        return BindingBuilder.bind(imageOperationQueue()).to(imageOperationExchange()).with("retry.*");
    }

    @Bean
    public Binding imageRetry1mBinding() {
        return BindingBuilder.bind(imageOperationRetry1mQueue()).to(imageOperationRetryExchange()).with("retry.1m");
    }

    @Bean
    public Binding imageRetry5mBinding() {
        return BindingBuilder.bind(imageOperationRetry5mQueue()).to(imageOperationRetryExchange()).with("retry.5m");
    }

    @Bean
    public Binding imageRetry15mBinding() {
        return BindingBuilder.bind(imageOperationRetry15mQueue()).to(imageOperationRetryExchange()).with("retry.15m");
    }

    @Bean
    public Binding imageRetry1hBinding() {
        return BindingBuilder.bind(imageOperationRetry1hQueue()).to(imageOperationRetryExchange()).with("retry.1h");
    }

    @Bean
    public Binding imageDlqBinding() {
        return BindingBuilder.bind(imageOperationDlq()).to(imageOperationDlx()).with(DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding imageOperationStepBinding() {
        return BindingBuilder.bind(imageOperationStepQueue())
                .to(imageOperationExchange())
                .with(ImageOperationRabbitNames.COMPENSATE_ROUTING_KEY);
    }

    @Bean
    public Binding imageOperationCopyStepBinding() {
        return BindingBuilder.bind(imageOperationStepQueue())
                .to(imageOperationExchange())
                .with(ImageOperationRabbitNames.COPY_ROUTING_KEY);
    }

    @Bean
    public Binding imageOperationRegisterImageDbStepBinding() {
        return BindingBuilder.bind(imageOperationStepQueue())
                .to(imageOperationExchange())
                .with(ImageOperationRabbitNames.REGISTER_IMAGE_DB_ROUTING_KEY);
    }

    @Bean
    public Binding imageOperationDeleteStagingStepBinding() {
        return BindingBuilder.bind(imageOperationStepQueue())
                .to(imageOperationExchange())
                .with(ImageOperationRabbitNames.DELETE_STAGING_ROUTING_KEY);
    }

    @Bean
    public Binding imageOperationDeleteObjectStepBinding() {
        return BindingBuilder.bind(imageOperationStepQueue())
                .to(imageOperationExchange())
                .with(ImageOperationRabbitNames.DELETE_OBJECT_ROUTING_KEY);
    }

    @Bean
    public Binding imageOperationDeleteFolderStepBinding() {
        return BindingBuilder.bind(imageOperationStepQueue())
                .to(imageOperationExchange())
                .with(ImageOperationRabbitNames.DELETE_FOLDER_ROUTING_KEY);
    }

    @Bean
    public Binding imageOperationStepRetryReturnBinding() {
        return BindingBuilder.bind(imageOperationStepQueue())
                .to(imageOperationExchange())
                .with(ImageOperationRabbitNames.STEP_RETRY_PREFIX + "*");
    }

    @Bean
    public Binding imageOperationStepRetry1mBinding() {
        return BindingBuilder.bind(imageOperationStepRetry1mQueue())
                .to(imageOperationRetryExchange())
                .with(ImageOperationRabbitNames.STEP_RETRY_PREFIX + "1m");
    }

    @Bean
    public Binding imageOperationStepRetry5mBinding() {
        return BindingBuilder.bind(imageOperationStepRetry5mQueue())
                .to(imageOperationRetryExchange())
                .with(ImageOperationRabbitNames.STEP_RETRY_PREFIX + "5m");
    }

    @Bean
    public Binding imageOperationStepRetry15mBinding() {
        return BindingBuilder.bind(imageOperationStepRetry15mQueue())
                .to(imageOperationRetryExchange())
                .with(ImageOperationRabbitNames.STEP_RETRY_PREFIX + "15m");
    }

    @Bean
    public Binding imageOperationStepRetry1hBinding() {
        return BindingBuilder.bind(imageOperationStepRetry1hQueue())
                .to(imageOperationRetryExchange())
                .with(ImageOperationRabbitNames.STEP_RETRY_PREFIX + "1h");
    }

    @Bean
    public Binding imageOperationStepDlqBinding() {
        return BindingBuilder.bind(imageOperationStepDlq())
                .to(imageOperationDlx())
                .with(ImageOperationRabbitNames.STEP_DLQ_ROUTING_KEY);
    }

    @Bean
    public MessageConverter imageCleanupMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    private Queue retryQueue(String name, int ttlMillis) {
        return QueueBuilder.durable(name)
                .ttl(ttlMillis)
                .deadLetterExchange(EXCHANGE)
                .build();
    }
}
