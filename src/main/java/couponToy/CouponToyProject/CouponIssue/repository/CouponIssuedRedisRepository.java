package couponToy.CouponToyProject.CouponIssue.repository;

import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * 쿠폰별 발급 자격(예약)을 Redis SET 에 기록한다. coupon:issued:{couponId} 에는 DB 확정 전 예약도 들어 있다.
 * 3단계 비교용 예약 방식 네 가지는 모두 "이미 예약됨 → 재고 소진 → 예약" 순서로 판단하고, 원자성만 다르다.
 */
@Repository
public class CouponIssuedRedisRepository {

    public enum Reservation { RESERVED, DUPLICATE, SOLD_OUT }

    private static final String ISSUED_KEY_PREFIX = "coupon:issued:";
    private static final String TOTAL_KEY_PREFIX = "coupon:total:";

    // SET_ONLY 와 같은 판단을 Redis 안에서 한 번에 실행한다. 0: 예약, 1: 이미 예약됨, 2: 재고 소진
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
              return 1
            end
            if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[2]) then
              return 2
            end
            redis.call('SADD', KEYS[1], ARGV[1])
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final FailurePoints failurePoints;
    private final int watchMaxAttempts;

    public CouponIssuedRedisRepository(StringRedisTemplate redisTemplate, FailurePoints failurePoints,
                                       @Value("${coupon.issue.redis.watch-max-attempts:10}") int watchMaxAttempts) {
        this.redisTemplate = redisTemplate;
        this.failurePoints = failurePoints;
        this.watchMaxAttempts = watchMaxAttempts;
    }

    /**
     * 트랜잭션 없이 명령 세 개를 따로 보낸다. 명령 하나하나는 원자적이지만,
     * SCARD 와 SADD 사이에 다른 클라이언트의 명령이 실행될 수 있다.
     */
    public Reservation reserveWithSetOnly(Long couponId, Long memberId, int totalCount) {
        String key = issuedKey(couponId);
        String member = String.valueOf(memberId);
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, member))) {   // 1. SISMEMBER
            return Reservation.DUPLICATE;
        }
        Long count = redisTemplate.opsForSet().size(key);                               // 2. SCARD
        if (count != null && count >= totalCount) {
            return Reservation.SOLD_OUT;
        }
        failurePoints.reach(Point.SET_ONLY_BEFORE_SADD, memberId);
        Long added = redisTemplate.opsForSet().add(key, member);                       // 3. SADD
        return added != null && added == 1 ? Reservation.RESERVED : Reservation.DUPLICATE;
    }

    /**
     * MULTI 로 SCARD, SADD 를 큐에 넣고 EXEC 로 한 번에 실행한다(이 둘 사이에는 아무것도 끼지 않는다).
     * 하지만 MULTI 안에서는 SCARD 결과를 보고 SADD 를 할지 고를 수 없으므로, 일단 넣은 뒤
     * Java 에서 EXEC 결과를 보고 재고 초과면 별도 SREM 으로 되돌린다.
     */
    @SuppressWarnings("unchecked")
    public Reservation reserveWithMultiExec(Long couponId, Long memberId, int totalCount) {
        String key = issuedKey(couponId);
        List<Object> results = redisTemplate.execute(new SessionCallback<>() {
            @Override
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                operations.multi();
                operations.opsForSet().size(key);
                operations.opsForSet().add(key, String.valueOf(memberId));
                return operations.exec();
            }
        });
        long countBefore = (Long) results.get(0);
        long added = (Long) results.get(1);

        if (added == 0) {
            return Reservation.DUPLICATE;
        }
        if (countBefore >= totalCount) {
            // 이 사이에는 재고보다 많은 회원이 SET 에 들어 있다.
            failurePoints.reach(Point.MULTI_EXEC_BEFORE_SREM, memberId);
            remove(couponId, memberId);
            return Reservation.SOLD_OUT;
        }
        return Reservation.RESERVED;
    }

    /**
     * WATCH 로 키를 감시한 채 읽고 판단한 뒤 MULTI/EXEC 로 SADD 한다.
     * 그 사이 다른 클라이언트가 키를 바꾸면 EXEC 가 실행되지 않으므로, 처음부터 다시 읽는다.
     * onAttempt 는 시도 번호(1부터)를 받는다(측정용).
     */
    @SuppressWarnings("unchecked")
    public Reservation reserveWithWatch(Long couponId, Long memberId, int totalCount, IntConsumer onAttempt) {
        String key = issuedKey(couponId);
        String member = String.valueOf(memberId);
        for (int attempt = 1; attempt <= watchMaxAttempts; attempt++) {
            onAttempt.accept(attempt);
            Object outcome = redisTemplate.execute(new SessionCallback<>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    operations.watch(key);
                    if (Boolean.TRUE.equals(operations.opsForSet().isMember(key, member))) {
                        operations.unwatch();
                        return Reservation.DUPLICATE;
                    }
                    Long count = operations.opsForSet().size(key);
                    if (count != null && count >= totalCount) {
                        operations.unwatch();
                        return Reservation.SOLD_OUT;
                    }
                    failurePoints.reach(Point.WATCH_BEFORE_EXEC, memberId);
                    operations.multi();
                    operations.opsForSet().add(key, member);
                    return operations.exec(); // 감시한 키가 바뀌었으면 실행되지 않고 빈 결과
                }
            });
            if (outcome instanceof Reservation reservation) {
                return reservation;
            }
            if (outcome instanceof List<?> list && !list.isEmpty()) {
                return Reservation.RESERVED;
            }
        }
        throw new OptimisticLockingFailureException("Redis WATCH 충돌이 " + watchMaxAttempts + "번 이어졌습니다.");
    }

    /**
     * 판단과 예약을 Lua 스크립트 하나로 실행한다. 스크립트가 도는 동안 다른 명령은 실행되지 않는다.
     */
    public Reservation reserveWithLua(Long couponId, Long memberId, int totalCount) {
        Long code = redisTemplate.execute(RESERVE_SCRIPT, List.of(issuedKey(couponId)),
                String.valueOf(memberId), String.valueOf(totalCount));
        return switch (code.intValue()) {
            case 0 -> Reservation.RESERVED;
            case 1 -> Reservation.DUPLICATE;
            default -> Reservation.SOLD_OUT;
        };
    }

    /**
     * 예약을 되돌린다(SREM).
     */
    public void remove(Long couponId, Long userId) {
        redisTemplate.opsForSet().remove(issuedKey(couponId), String.valueOf(userId));
    }

    /**
     * 쿠폰 생성 시 totalCount 를 캐싱한다. (변경되지 않는 값이므로 만료 없음)
     */
    public void setTotalCount(Long couponId, int totalCount) {
        redisTemplate.opsForValue().set(TOTAL_KEY_PREFIX + couponId, String.valueOf(totalCount));
    }

    /**
     * 캐시에서 totalCount 를 조회한다. Redis 유실 시 empty 를 반환한다.
     */
    public Optional<Integer> getTotalCount(Long couponId) {
        String value = redisTemplate.opsForValue().get(TOTAL_KEY_PREFIX + couponId);
        return Optional.ofNullable(value).map(Integer::parseInt);
    }

    /**
     * Redis SET 에 등록된 회원 수(SCARD). 키가 없으면 0.
     */
    public long issuedCount(Long couponId) {
        Long size = redisTemplate.opsForSet().size(issuedKey(couponId));
        return size == null ? 0 : size;
    }

    public Set<Long> members(Long couponId) {
        Set<String> members = redisTemplate.opsForSet().members(issuedKey(couponId));
        return members == null ? Set.of() : members.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    private String issuedKey(Long couponId) {
        return ISSUED_KEY_PREFIX + couponId;
    }
}
