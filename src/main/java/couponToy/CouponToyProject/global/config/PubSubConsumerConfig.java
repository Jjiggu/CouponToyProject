package couponToy.CouponToyProject.global.config;

import couponToy.CouponToyProject.CouponIssue.service.PubSubIssueConsumer;
import couponToy.CouponToyProject.CouponIssue.service.PubSubIssueCouponService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 5단계 구독자 프로세스: pubsub-consumer 프로필로 띄운 프로세스만 채널을 구독한다(발행자와 같은 코드, 실행 설정만 다름).
 * 받은 메시지는 4단계와 같은 스레드 풀(couponIssueExecutor)의 작업 스레드가 처리한다. 전달 방식만 4단계와 다르다.
 */
@Configuration
@Profile("pubsub-consumer")
public class PubSubConsumerConfig {

    @Bean
    public RedisMessageListenerContainer pubSubIssueContainer(RedisConnectionFactory connectionFactory,
                                                              PubSubIssueConsumer consumer,
                                                              ThreadPoolTaskExecutor couponIssueExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(couponIssueExecutor);
        container.addMessageListener(consumer, new ChannelTopic(PubSubIssueCouponService.CHANNEL));
        return container;
    }
}
