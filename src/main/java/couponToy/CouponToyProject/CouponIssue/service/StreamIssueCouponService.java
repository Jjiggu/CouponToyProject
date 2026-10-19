package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.service.RedisIssueCouponService.ReservationMethod;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 6단계 발행자: Redis 예약 뒤 확정 요청을 Redis Stream 에 넣고(XADD) 바로 돌아온다. 메시지는 스트림에 남는다.
 * 확정은 Consumer Group 의 소비자(StreamIssueConsumer)가 하고, DB 커밋 뒤에 ACK 한다.
 */
@Service
@RequiredArgsConstructor
public class StreamIssueCouponService {

    public static final String STREAM = "coupon:issue:stream";
    public static final String DEAD_LETTER = "coupon:issue:stream:dead";
    public static final String GROUP = "coupon-issuers";

    // 스트림 전체 상태. 대기 = 그룹에 아직 전달되지 않은 메시지, pending = 전달됐지만 ACK 되지 않은 메시지
    public record StreamState(long length, long waiting, long pending, long deadLetters) {
    }

    private final RedisIssueCouponService redisIssueCouponService;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final StringRedisTemplate redisTemplate;
    private final FailurePoints failurePoints;

    // 4·5단계와 같은 Lua 예약 뒤 XADD 를 따로 보낸다. 두 명령 사이에서 프로세스가 죽으면 예약만 남고 메시지는 없다.
    public void issue(Long memberId, Long couponId) {
        redisIssueCouponService.reserve(memberId, couponId, ReservationMethod.LUA, attempt -> {
        });
        failurePoints.reach(Point.AFTER_RESERVE, memberId);
        try {
            redisTemplate.opsForStream().add(StreamRecords.string(message(memberId, couponId)).withStreamKey(STREAM));
        } catch (RuntimeException e) {
            // XADD 가 실패했다. 메시지를 남기지 못했으므로 예약을 되돌린다.
            couponIssuedRedisRepository.remove(couponId, memberId);
            throw e;
        }
    }

    // 최소 개선: 예약과 XADD 를 Lua 스크립트 하나로 실행한다. 예약만 있고 메시지가 없는 상태가 생기지 않는다.
    public void issueAtomically(Long memberId, Long couponId) {
        redisIssueCouponService.reserveAndEnqueue(memberId, couponId, STREAM);
    }

    public StreamState state() {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(STREAM))) {
            return new StreamState(0, 0, 0, size(DEAD_LETTER));
        }
        XInfoGroup group = redisTemplate.opsForStream().groups(STREAM).stream()
                .filter(g -> GROUP.equals(g.groupName())).findFirst().orElse(null);
        long length = size(STREAM);
        if (group == null) {
            return new StreamState(length, length, 0, size(DEAD_LETTER));
        }
        long waiting = redisTemplate.opsForStream().range(STREAM, Range.leftOpen(group.lastDeliveredId(), "+")).size();
        return new StreamState(length, waiting, group.pendingCount(), size(DEAD_LETTER));
    }

    static Map<String, String> message(Long memberId, Long couponId) {
        return Map.of("memberId", String.valueOf(memberId), "couponId", String.valueOf(couponId));
    }

    private long size(String key) {
        Long size = redisTemplate.opsForStream().size(key);
        return size == null ? 0 : size;
    }
}
