package couponToy.CouponToyProject.queue.scheduler;

import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.queue.repository.CouponQueueRepository;
import couponToy.CouponToyProject.queue.service.CouponQueueSchedulerService;
import couponToy.CouponToyProject.queue.util.RedisKeyUtils;
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
    private final StringRedisTemplate redisTemplate;
    private final CouponQueueSchedulerService queueSchedulerService;

    // 한 번에 처리할 사용자 수 (예: 상위 5명)
    private static final long PROCESS_COUNT = 5;
    private static final String WAITING_KEY_PREFIX = "coupon:waiting:";
    private static final String FAILED_KEY_PREFIX = "coupon:failed:";

    /**
     * 1초마다 실행하여, Redis에 저장된 모든 coupon:waiting:* 키를 동적으로 조회한 후,
     * 각 쿠폰 대기열에 대해 상위 PROCESS_COUNT명의 사용자에 대해 발급 처리를 시도한다.
     */
    @Scheduled(fixedDelay = 500)
    public void processQueue() {
        Set<String> waitingKeys = getAllQueueKeys(WAITING_KEY_PREFIX);

        if (waitingKeys.isEmpty()) {
            return;
        }
        for (String key : waitingKeys) {
            Long couponId = extractCouponIdFromKey(WAITING_KEY_PREFIX, key);
            processQueueForCoupon(couponId);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void processFailedQueue() {
        Set<String> failedKeys = getAllQueueKeys(FAILED_KEY_PREFIX);

        if (failedKeys.isEmpty()) {
            return;
        }
        for (String key : failedKeys) {
            Long couponId = extractCouponIdFromKey(FAILED_KEY_PREFIX, key);
            processFailedQueueForCoupon(couponId);
        }
    }


    /**
     * SCAN 명령어를 사용해 모든 "coupon:waiting:*" 키를 조회한다.
     */
    private Set<String> getAllQueueKeys(String prefix) {
        Set<String> keys = new HashSet<>();
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            return keys;
        }
        try (RedisConnection connection = factory.getConnection()) {
            ScanOptions scanOptions = ScanOptions.scanOptions()
                    .match(prefix + "*")
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
                queueSchedulerService.issueFromQueue(couponId, userId);

                couponQueueRepository.removeUserFromQueue(couponId, userId);
                couponQueueRepository.addToIssuedSet(couponId, userId);
                log.info("쿠폰 발급 완료 - couponId: {}, userId: {}", couponId, userId);

            } catch (CouponSoldOutException e) {
                // 발급 수량이 소진된 경우, 대기열을 전체 삭제(또는 다른 처리를 수행)
                log.info("쿠폰 발급 수량 소진 - couponId: {}", couponId);
                clearQueue(WAITING_KEY_PREFIX, couponId);
                break;

            } catch (Exception e) {
                log.error("쿠폰 발급 처리 중 오류 발생 - couponId: {}, userId: {}", couponId, userIdStr, e);
                Long userId = Long.valueOf(userIdStr);

                couponQueueRepository.removeUserFromQueue(couponId, userId);
                couponQueueRepository.addToFailedQueue(couponId, userId);
            }
        }
    }


    /**
     * 특정 쿠폰의 실패 큐에서 상위 PROCESS_COUNT_FAILED명의 사용자를 재처리합니다.
     */
    private void processFailedQueueForCoupon(Long couponId) {
        Set<String> failedUserIds = couponQueueRepository.getTopFailedNUsers(couponId, PROCESS_COUNT);
        if (failedUserIds == null || failedUserIds.isEmpty()) {
            return;
        }
        for (String userIdStr : failedUserIds) {
            try {
                Long userId = Long.valueOf(userIdStr);

                queueSchedulerService.issueFromQueue(couponId, userId);
                // 재처리 성공 시 실패 큐에서 제거
                couponQueueRepository.removeUserFromFailedQueue(couponId, userId);

                log.info("재처리 성공 - couponId: {}, userId: {}", couponId, userId);
            } catch (CouponSoldOutException e) {
                // 발급 수량이 소진된 경우, 대기열을 전체 삭제(또는 다른 처리를 수행)
                log.info("쿠폰 발급 수량 소진 - couponId: {}", couponId);
                clearQueue(FAILED_KEY_PREFIX, couponId);
                break;
            } catch (Exception e) {
                Long userId = Long.valueOf(userIdStr);

                // TODO 반복 실패 CASE 처리 추가
                couponQueueRepository.removeUserFromFailedQueue(couponId, userId);

                log.error("재처리 실패 - couponId: {}, userId: {}. 에러: {}", couponId, userIdStr, e.getMessage(), e);
            }
        }
    }


    /**
     * 대기열 키("coupon:waiting:{couponId}")에서 couponId를 추출한다.
     */
    private Long extractCouponIdFromKey(String prefix, String key) {
        return RedisKeyUtils.extractCouponIdFromKey(prefix, key);
    }


    /**
     * 특정 쿠폰의 대기열 전체를 삭제한다.
     */
    private void clearQueue(String prefix, Long couponId) {
        String key = prefix.equals(RedisKeyUtils.WAITING_KEY_PREFIX) ?
                RedisKeyUtils.buildQueueKey(couponId)
                : RedisKeyUtils.buildFailedKey(couponId);
        redisTemplate.delete(key);
    }
}
