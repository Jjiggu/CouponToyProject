package couponToy.CouponToyProject.CouponIssue.repository;

import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CouponIssuedRedisRepository {

    private final StringRedisTemplate redisTemplate;

    private static final String ISSUED_KEY_PREFIX = "coupon:issued:";
    private static final String TOTAL_KEY_PREFIX  = "coupon:total:";

    /**
     * Redis MULTI/EXEC를 통해 SCARD + SADD를 원자적으로 실행한다.
     * - SADD 결과 0 → 이미 발급된 사용자 (IsAlreadyIssued)
     * - SCARD 결과 >= totalCount → 품절 (SREM 롤백 후 CouponSoldOutException)
     */
    @SuppressWarnings("unchecked")
    public void checkAndRegister(Long couponId, Long userId, int totalCount) {
        String key = buildIssuedKey(couponId);

        List<Object> results = redisTemplate.execute(new SessionCallback<>() {
            @Override
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                operations.multi();
                operations.opsForSet().size(key);
                operations.opsForSet().add(key, String.valueOf(userId));
                return operations.exec();
            }
        });

        Long currentCount = (Long) results.get(0);
        Long addResult = (Long) results.get(1);

        if (addResult == 0L) {
            throw new IsAlreadyIssued(ErrorCode.IS_ALREADY_ISSUED);
        }
        if (currentCount >= totalCount) {
            remove(couponId, userId);
            throw new CouponSoldOutException(ErrorCode.COUPON_SOLD_OUT);
        }
    }

    /**
     * DB 발급 실패 시 Redis 상태를 롤백한다.
     */
    public void remove(Long couponId, Long userId) {
        redisTemplate.opsForSet().remove(ISSUED_KEY_PREFIX + couponId, String.valueOf(userId));
    }

    /**
     * 쿠폰 생성 시 totalCount를 캐싱한다. (변경되지 않는 값이므로 만료 없음)
     */
    public void setTotalCount(Long couponId, int totalCount) {
        redisTemplate.opsForValue().set(TOTAL_KEY_PREFIX + couponId, String.valueOf(totalCount));
    }

    /**
     * 캐시에서 totalCount를 조회한다. Redis 유실 시 empty를 반환한다.
     */
    public Optional<Integer> getTotalCount(Long couponId) {
        String value = redisTemplate.opsForValue().get(TOTAL_KEY_PREFIX + couponId);
        return Optional.ofNullable(value).map(Integer::parseInt);
    }

    /**
     * Redis SET 에 등록된 사용자 수(SCARD). 키가 없으면 0.
     */
    public long issuedCount(Long couponId) {
        Long size = redisTemplate.opsForSet().size(buildIssuedKey(couponId));
        return size == null ? 0 : size;
    }

    private String buildIssuedKey(Long couponId) {
        return ISSUED_KEY_PREFIX + couponId;
    }
}
