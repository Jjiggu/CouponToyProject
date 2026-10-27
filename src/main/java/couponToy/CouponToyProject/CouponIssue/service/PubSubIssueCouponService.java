package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.service.RedisIssueCouponService.ReservationMethod;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 5단계 발행자: Redis 예약(3단계 Lua)까지 하고, DB 확정 요청을 Redis Pub/Sub 채널에 PUBLISH 한 뒤 바로 돌아온다.
 * Pub/Sub 는 메시지를 저장하지 않는다. 그 순간 채널을 구독 중인 연결에만 보내고 잊는다.
 * PUBLISH 가 돌려주는 수는 "받은 구독자 수" 일 뿐, 확정 여부와 관계없다.
 */
@Service
@RequiredArgsConstructor
public class PubSubIssueCouponService {

    // Redis Pub/Sub 채널은 DB 번호(SELECT)와 관계없이 서버 전체에서 하나다
    public static final String CHANNEL = "coupon:issue:requests";

    private final RedisIssueCouponService redisIssueCouponService;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final StringRedisTemplate redisTemplate;
    private final FailurePoints failurePoints;

    public long issue(Long memberId, Long couponId) {
        redisIssueCouponService.reserve(memberId, couponId, ReservationMethod.LUA, attempt -> {
        });
        failurePoints.reach(Point.AFTER_RESERVE, memberId);
        try {
            Long receivers = redisTemplate.convertAndSend(CHANNEL, memberId + ":" + couponId);
            return receivers == null ? 0 : receivers;
        } catch (RuntimeException e) {
            // PUBLISH 자체가 실패했다. 보내지 못했으므로 예약을 되돌린다.
            couponIssuedRedisRepository.remove(couponId, memberId);
            throw e;
        }
    }
}
