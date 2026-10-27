package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 5단계 구독자: 받은 메시지마다 3단계와 같은 DB 확정·보상(confirmOrCancel)을 실행한다.
 * 이 빈은 어느 프로세스에나 있지만, 채널 구독은 pubsub-consumer 프로필(PubSubConsumerConfig)이나 테스트가 만든 컨테이너만 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PubSubIssueConsumer implements MessageListener {

    private final RedisIssueCouponService redisIssueCouponService;
    private final FailurePoints failurePoints;

    // 결과별 처리 수(SUCCESS 또는 예외 이름). 발행자는 결과를 모르므로 구독자 쪽에서 센다
    private final Map<String, LongAdder> results = new ConcurrentHashMap<>();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String[] body = new String(message.getBody(), StandardCharsets.UTF_8).split(":");
        long memberId = Long.parseLong(body[0]);
        long couponId = Long.parseLong(body[1]);
        failurePoints.reach(Point.PUBSUB_BEFORE_CONFIRM, memberId);
        try {
            redisIssueCouponService.confirmOrCancel(memberId, couponId);
            count("SUCCESS");
        } catch (RuntimeException e) {
            count(e.getClass().getSimpleName());
            log.warn("pubsub confirm failed memberId={} couponId={} {}", memberId, couponId, e.getClass().getSimpleName());
        }
    }

    public Map<String, Long> results() {
        Map<String, Long> snapshot = new TreeMap<>();
        results.forEach((k, v) -> snapshot.put(k, v.sum()));
        return snapshot;
    }

    private void count(String result) {
        results.computeIfAbsent(result, k -> new LongAdder()).increment();
    }
}
