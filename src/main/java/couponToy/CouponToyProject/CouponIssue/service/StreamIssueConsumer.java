package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import io.lettuce.core.Limit;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.models.stream.PendingMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService.DEAD_LETTER;
import static couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService.GROUP;
import static couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService.STREAM;

/**
 * 6단계 소비자: Consumer Group 으로 메시지를 받아 3단계와 같은 DB 확정(IssueConfirmService)을 하고, 커밋 뒤에만 ACK 한다.
 * 이 빈은 어느 프로세스에나 있지만, 스트림을 읽는 것은 stream-consumer 프로필(StreamConsumerConfig)이나 테스트가 만든 컨테이너뿐이다.
 *
 * 3~5단계는 확정이 실패하면 바로 예약을 되돌렸다(SREM). 여기서는 메시지가 남아 다시 처리되므로,
 * 일시적 실패에 바로 되돌리면 재처리가 성공했을 때 Redis 가 실제 발급자를 잊는다(5단계 4-5). 그래서 되돌리기는
 * 다시 해도 결과가 같은 거절(품절, 없는 회원·쿠폰)이나, 전달 횟수 상한을 넘겨 따로 보관할 때만 한다.
 */
@Slf4j
@Component
public class StreamIssueConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final IssueConfirmService issueConfirmService;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final StringRedisTemplate redisTemplate;
    private final FailurePoints failurePoints;
    private final long maxDeliveries;

    // 처리 결과별 수. 발행자는 결과를 모르므로 소비자 쪽에서 센다
    private final Map<String, LongAdder> results = new ConcurrentHashMap<>();

    public StreamIssueConsumer(IssueConfirmService issueConfirmService, CouponIssuedRedisRepository couponIssuedRedisRepository,
                               StringRedisTemplate redisTemplate, FailurePoints failurePoints,
                               @Value("${coupon.issue.stream.max-deliveries:3}") long maxDeliveries) {
        this.issueConfirmService = issueConfirmService;
        this.couponIssuedRedisRepository = couponIssuedRedisRepository;
        this.redisTemplate = redisTemplate;
        this.failurePoints = failurePoints;
        this.maxDeliveries = maxDeliveries;
    }

    // 그룹이 없으면 만든다(스트림도 없으면 함께). 0 부터 읽으므로 그룹이 생기기 전에 쌓인 메시지도 처리한다.
    public void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
        } catch (RedisSystemException e) {
            if (e.getMostSpecificCause().getMessage() == null || !e.getMostSpecificCause().getMessage().startsWith("BUSYGROUP")) {
                throw e;
            }
        }
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        handle(record.getId().getValue(), record.getValue());
    }

    void handle(String id, Map<String, String> body) {
        long memberId = Long.parseLong(body.get("memberId"));
        long couponId = Long.parseLong(body.get("couponId"));
        failurePoints.reach(Point.STREAM_BEFORE_CONFIRM, memberId);
        try {
            issueConfirmService.confirm(memberId, couponId);
            count("SUCCESS");
        } catch (DataIntegrityViolationException e) {
            // 이미 발급된 회원: 커밋 뒤 ACK 전에 죽었다가 다시 처리하는 경우도 여기로 온다. 끝난 일이므로 ACK 한다.
            count("ALREADY_ISSUED");
            log.info("stream message already issued id={} memberId={}", id, memberId);
        } catch (CouponSoldOutException | MemberNotFoundException | CouponNotFoundException e) {
            // 다시 해도 같은 거절이다. 예약을 되돌리고 ACK 한다.
            couponIssuedRedisRepository.remove(couponId, memberId);
            count("REJECTED_" + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            // 일시적일 수 있는 실패: ACK 하지 않는다. 메시지는 pending 에 남아 회수(reclaim) 뒤 다시 처리된다. 예약도 남긴다.
            count("RETRY_" + e.getClass().getSimpleName());
            log.warn("stream confirm failed, will retry id={} memberId={} {}", id, memberId, e.getClass().getSimpleName());
            return;
        }
        failurePoints.reach(Point.STREAM_BEFORE_ACK, memberId);
        redisTemplate.opsForStream().acknowledge(STREAM, GROUP, id);
    }

    /**
     * minIdle 넘게 ACK 되지 않은 메시지를 consumerName 앞으로 가져와(XAUTOCLAIM) 다시 처리한다.
     * 전달 횟수가 상한을 넘은 메시지는 따로 보관하고(DEAD_LETTER), 예약을 되돌린 뒤 ACK 한다. 처리한 메시지 수를 돌려준다.
     */
    public int reclaim(String consumerName, Duration minIdle) {
        ClaimedMessages<byte[], byte[]> claimed = native_(commands -> commands.xautoclaim(bytes(STREAM),
                XAutoClaimArgs.Builder.xautoclaim(io.lettuce.core.Consumer.from(bytes(GROUP), bytes(consumerName)), minIdle, "0-0").count(100)).get());
        for (StreamMessage<byte[], byte[]> message : claimed.getMessages()) {
            Map<String, String> body = new TreeMap<>();
            message.getBody().forEach((k, v) -> body.put(text(k), text(v)));
            long deliveries = deliveries(message.getId());
            if (deliveries > maxDeliveries) {
                deadLetter(message.getId(), body, deliveries);
            } else {
                handle(message.getId(), body);
            }
        }
        return claimed.getMessages().size();
    }

    public Map<String, Long> results() {
        Map<String, Long> snapshot = new TreeMap<>();
        results.forEach((k, v) -> snapshot.put(k, v.sum()));
        return snapshot;
    }

    private void deadLetter(String id, Map<String, String> body, long deliveries) {
        Map<String, String> dead = new TreeMap<>(body);
        dead.put("originalId", id);
        dead.put("deliveries", String.valueOf(deliveries));
        redisTemplate.opsForStream().add(StreamRecords.string(dead).withStreamKey(DEAD_LETTER));
        couponIssuedRedisRepository.remove(Long.parseLong(body.get("couponId")), Long.parseLong(body.get("memberId")));
        redisTemplate.opsForStream().acknowledge(STREAM, GROUP, id);
        count("DEAD_LETTER");
        log.warn("stream message moved to dead letter id={} memberId={} deliveries={}", id, body.get("memberId"), deliveries);
    }

    // XPENDING 으로 본 이 메시지의 전달 횟수 (XAUTOCLAIM 이 가져가며 1 늘린 뒤의 값)
    private long deliveries(String id) {
        List<PendingMessage> pending = native_(commands -> commands.xpending(bytes(STREAM), bytes(GROUP),
                io.lettuce.core.Range.create(id, id), Limit.from(1)).get());
        return pending.isEmpty() ? 0 : pending.get(0).getRedeliveryCount();
    }

    private void count(String result) {
        results.computeIfAbsent(result, k -> new LongAdder()).increment();
    }

    private interface NativeCall<T> {
        T call(RedisClusterAsyncCommands<byte[], byte[]> commands) throws Exception;
    }

    // ponytail: Spring Data Redis 3.4 에는 XAUTOCLAIM 이 없어 Lettuce 명령을 직접 쓴다
    @SuppressWarnings("unchecked")
    private <T> T native_(NativeCall<T> call) {
        return redisTemplate.execute((RedisCallback<T>) connection -> {
            try {
                return call.call((RedisClusterAsyncCommands<byte[], byte[]>) connection.getNativeConnection());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
