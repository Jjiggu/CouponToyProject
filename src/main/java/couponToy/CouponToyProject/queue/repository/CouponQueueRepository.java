package couponToy.CouponToyProject.queue.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
@RequiredArgsConstructor
public class CouponQueueRepository {

    private final StringRedisTemplate redisTemplate;
    private static final String WAITING_KEY_PREFIX = "coupon:waiting:";
    private static final String ISSUED_KEY_PREFIX = "coupon:issued:";


    public String buildQueueKey(Long couponId) {
        return WAITING_KEY_PREFIX + couponId;
    }

    private String buildIssuedKey(Long couponId) {
        return ISSUED_KEY_PREFIX + couponId;
    }

    public void addToWaitingQueue(Long couponId, Long userId) {
        String key = buildQueueKey(couponId);
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(key, String.valueOf(userId), score);
    }

    public Long getUserRank(Long couponId, Long userId) {
        String key = buildQueueKey(couponId);
        return redisTemplate.opsForZSet().rank(key, String.valueOf(userId));
    }

    public Set<String> getTopNUsers(Long couponId, Long count) {
        String key = buildQueueKey(couponId);
        return redisTemplate.opsForZSet().range(key, 0, count - 1);
    }

    public void removeUserFromQueue(Long couponId, Long userId) {
        String key = buildQueueKey(couponId);
        redisTemplate.opsForZSet().remove(key, String.valueOf(userId));
    }

    public void addToIssuedSet(Long couponId, Long userId) {
        String key = buildIssuedKey(couponId);
        redisTemplate.opsForZSet().add(key, String.valueOf(userId), System.currentTimeMillis());
    }

    public boolean isAlreadyIssued(Long couponId, Long userId) {
        String key = buildIssuedKey(couponId);
        Double score = redisTemplate.opsForZSet().score(key, String.valueOf(userId));

        System.out.println("check issued - userId: " + userId + ", score: " + score);

        return score != null;
    }

    public Long getQueueSize(Long couponId) {
        String key = buildQueueKey(couponId);
        return redisTemplate.opsForZSet().size(key);
    }
}
