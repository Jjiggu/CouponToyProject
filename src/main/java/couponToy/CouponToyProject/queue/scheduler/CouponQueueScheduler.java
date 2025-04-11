package couponToy.CouponToyProject.queue.scheduler;

import couponToy.CouponToyProject.CouponIssue.service.IssueCouponService;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.queue.repository.CouponQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@EnableScheduling
@Component
@RequiredArgsConstructor
public class CouponQueueScheduler {

    private final CouponQueueRepository couponQueueRepository;
    private final IssueCouponService issueCouponService;
    private final StringRedisTemplate redisTemplate;

    // 한 번에 처리할 사용자 수 (예: 상위 5명)
    private static final long PROCESS_COUNT = 5;
    private static final String WAITING_KEY_PREFIX = "coupon:waiting:";

    /**
     * 1초마다 실행하여, Redis에 저장된 모든 coupon:waiting:* 키를 동적으로 조회한 후,
     * 각 쿠폰 대기열에 대해 상위 PROCESS_COUNT명의 사용자에 대해 발급 처리를 시도한다.
     */
    @Scheduled(fixedDelay = 500)
    public void processQueue() {
        Set<String> waitingKeys = getAllWaitingQueueKeys();
        if (waitingKeys.isEmpty()) {
            return;
        }
        for (String key : waitingKeys) {
            Long couponId = extractCouponIdFromKey(key);
            processQueueForCoupon(couponId);
        }
    }

    /**
     * SCAN 명령어를 사용해 모든 "coupon:waiting:*" 키를 조회한다.
     */
    private Set<String> getAllWaitingQueueKeys() {
        Set<String> keys = new HashSet<>();
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            return keys;
        }
        try (RedisConnection connection = factory.getConnection()) {
            ScanOptions scanOptions = ScanOptions.scanOptions()
                    .match(WAITING_KEY_PREFIX + "*")
                    .count(1000)
                    .build();
            Cursor<byte[]> cursor = connection.scan(scanOptions);
            while (cursor.hasNext()) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                keys.add(key);
            }
        } catch (Exception ex) {
            log.error("대기열 키 조회 중 오류 발생", ex);
        }
        return keys;
    }

    /**
     * 대기열 키("coupon:waiting:{couponId}")에서 couponId를 추출한다.
     */
    private Long extractCouponIdFromKey(String key) {
        String idPart = key.substring(WAITING_KEY_PREFIX.length());
        return Long.valueOf(idPart);
    }

    /**
     * 특정 쿠폰의 대기열에서 상위 PROCESS_COUNT명의 사용자를 조회하여 발급 처리를 시도한다.
     */
    private void processQueueForCoupon(Long couponId) {
        Set<String> waitingUserIds = couponQueueRepository.getTopNUsers(couponId, PROCESS_COUNT);
        if (waitingUserIds == null || waitingUserIds.isEmpty()) {
            return;
        }
        for (String userIdStr : waitingUserIds) {
            try {
                Long userId = Long.valueOf(userIdStr);
                issueCouponService.issueFromQueue(couponId, userId);

                couponQueueRepository.removeUserFromQueue(couponId, userId);
                couponQueueRepository.addToIssuedSet(couponId, userId);
                log.info("쿠폰 발급 완료 - couponId: {}, userId: {}", couponId, userId);

            } catch (CouponSoldOutException e) {
                // 발급 수량이 소진된 경우, 대기열을 전체 삭제(또는 다른 처리를 수행)
                log.info("쿠폰 발급 수량 소진 - couponId: {}", couponId);
                clearWaitingQueue(couponId);
                break;

            } catch (Exception e) {
                log.error("쿠폰 발급 처리 중 오류 발생 - couponId: {}, userId: {}", couponId, userIdStr, e);
            }
        }
    }

    /**
     * 특정 쿠폰의 대기열 전체를 삭제한다.
     */
    private void clearWaitingQueue(Long couponId) {
        String key = WAITING_KEY_PREFIX + couponId;
        redisTemplate.delete(key);
    }
}
