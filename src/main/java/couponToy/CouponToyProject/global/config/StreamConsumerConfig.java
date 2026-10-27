package couponToy.CouponToyProject.global.config;

import couponToy.CouponToyProject.CouponIssue.service.StreamIssueConsumer;
import couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * 6단계 소비자 프로세스: stream-consumer 프로필로 띄운 프로세스만 스트림을 읽는다(발행자와 같은 코드, 실행 설정만 다름).
 * 같은 그룹에 소비자를 consumers 개 등록한다. 소비자마다 스레드 하나가 XREADGROUP 으로 읽고 차례로 처리한다.
 * 동시 처리 수를 늘릴 때 작업을 스레드 풀에 넘기지 않고 소비자를 늘린다. 넘기면 읽은 메시지가 다시 메모리 큐에 쌓인다(4단계).
 */
@Configuration
@Profile("stream-consumer")
@EnableScheduling
public class StreamConsumerConfig {

    // 소비자 이름은 프로세스마다 다르다. 죽은 프로세스의 pending 은 다른 이름의 소비자가 XAUTOCLAIM 으로 가져온다
    private final String name = "consumer-" + ProcessHandle.current().pid();
    private final StreamIssueConsumer consumer;
    private final Duration claimMinIdle;

    public StreamConsumerConfig(StreamIssueConsumer consumer,
                                @Value("${coupon.issue.stream.claim-min-idle-ms:5000}") long claimMinIdleMs) {
        this.consumer = consumer;
        this.claimMinIdle = Duration.ofMillis(claimMinIdleMs);
    }

    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamIssueContainer(
            RedisConnectionFactory connectionFactory, @Value("${coupon.issue.stream.consumers:4}") int consumers) {
        consumer.ensureGroup();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options = StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(500))
                .batchSize(10)
                .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container = StreamMessageListenerContainer.create(connectionFactory, options);
        for (int i = 1; i <= consumers; i++) {
            container.receive(Consumer.from(StreamIssueCouponService.GROUP, name + "-" + i),
                    StreamOffset.create(StreamIssueCouponService.STREAM, ReadOffset.lastConsumed()), consumer);
        }
        container.start();
        return container;
    }

    // claimMinIdle 넘게 ACK 되지 않은 메시지(죽은 소비자의 것, 일시적 실패로 남은 것)를 가져와 다시 처리한다
    @Scheduled(fixedDelayString = "${coupon.issue.stream.reclaim-interval-ms:1000}")
    public void reclaim() {
        consumer.reclaim(name + "-reclaimer", claimMinIdle);
    }
}
