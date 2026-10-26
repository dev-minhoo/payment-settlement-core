package com.minhoo.paymentsettlementcore.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 토픽이 없으면 기동 시 KafkaAdmin이 만든다. 이미 있으면 그대로 둔다. */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentEventsTopic(@Value("${psc.kafka.topic.payment-events}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
