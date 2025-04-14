package couponToy.CouponToyProject.queue.repository;

import couponToy.CouponToyProject.queue.util.RedisKeyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CouponQueueRepository {

    private final StringRedisTemplate redisTemplate;

    public void addToWaitingQueue(Long couponId, Long userId) {
        String key = RedisKeyUtils.buildQueueKey(couponId);
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(key, String.valueOf(userId), score);
    }


    public void addToFailedQueue(Long couponId, Long userId) {
        String key = RedisKeyUtils.buildFailedKey(couponId);
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(key, String.valueOf(userId), score);
    }


    public Long getUserRank(Long couponId, Long userId) {
        String key = RedisKeyUtils.buildQueueKey(couponId);

        try {
            Long rank = redisTemplate.opsForZSet().rank(key, String.valueOf(userId));
            return rank != null ? rank : -1L;
        } catch (Exception e) {
            log.error("사용자 순위 조회 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("Redis 작업 실패", e);
        }
    }


    public Set<String> getTopNUsers(Long couponId, Long count) {
        String key = RedisKeyUtils.buildQueueKey(couponId);
        return redisTemplate.opsForZSet().range(key, 0, count - 1);
    }


    public Set<String> getTopFailedNUsers(Long couponId, Long count) {
        String key = RedisKeyUtils.buildFailedKey(couponId);
        return redisTemplate.opsForZSet().range(key, 0, count - 1);
    }


    public void removeUserFromQueue(Long couponId, Long userId) {
        String key = RedisKeyUtils.buildQueueKey(couponId);
        redisTemplate.opsForZSet().remove(key, String.valueOf(userId));
    }


    public void removeUserFromFailedQueue(Long couponId, Long userId) {
        String key = RedisKeyUtils.buildFailedKey(couponId);
        redisTemplate.opsForZSet().remove(key, String.valueOf(userId));
    }


    public void addToIssuedSet(Long couponId, Long userId) {
        String key = RedisKeyUtils.buildIssuedKey(couponId);
        redisTemplate.opsForZSet().add(key, String.valueOf(userId), System.currentTimeMillis());
    }


    public boolean isAlreadyIssued(Long couponId, Long userId) {
        String key = RedisKeyUtils.buildIssuedKey(couponId);
        Double score = redisTemplate.opsForZSet().score(key, String.valueOf(userId));

        log.debug("check issued - userId: {}, score: {}", userId, score);

        return score != null;
    }


    public Long getQueueSize(Long couponId) {
        String key = RedisKeyUtils.buildIssuedKey(couponId);
        return redisTemplate.opsForZSet().size(key);
    }
}
