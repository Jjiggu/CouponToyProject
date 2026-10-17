package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.event.CouponIssuedEvent;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class EventIssueCouponService {

    private final CouponRepository couponRepository;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CouponIssueMetrics metrics;

    @Transactional
    public IssueCouponResponse issueCoupon(Long userId, Long couponId) {
        CouponIssueStrategy strategy = CouponIssueStrategy.REDIS_EVENT;
        Timer.Sample sample = metrics.start();

        try {
            int totalCount = findTotalCount(strategy, couponId);

            metrics.recordRedis(strategy, () -> {
                couponIssuedRedisRepository.checkAndRegister(couponId, userId, totalCount);
                return null;
            });

            CouponIssuedEvent event = new CouponIssuedEvent(userId, couponId, Instant.now());
            metrics.recordEventPublished();
            try {
                eventPublisher.publishEvent(event);
            } catch (RuntimeException ex) {
                metrics.recordEventFailed(Duration.ZERO);
                couponIssuedRedisRepository.remove(couponId, userId);
                throw ex;
            }

            metrics.recordRequest(strategy, CouponIssueResult.ACCEPTED, sample);
            return IssueCouponResponse.builder()
                    .memberId(userId)
                    .couponId(couponId)
                    .build();
        } catch (IsAlreadyIssued ex) {
            metrics.recordRequest(strategy, CouponIssueResult.DUPLICATE, sample);
            throw ex;
        } catch (CouponSoldOutException ex) {
            metrics.recordRequest(strategy, CouponIssueResult.SOLD_OUT, sample);
            throw ex;
        } catch (Exception ex) {
            couponIssuedRedisRepository.remove(couponId, userId);
            metrics.recordRequest(strategy, CouponIssueResult.ERROR, sample);
            throw ex;
        }
    }

    private int findTotalCount(CouponIssueStrategy strategy, Long couponId) {
        return metrics.recordRedis(strategy, () -> couponIssuedRedisRepository.getTotalCount(couponId))
                .orElseGet(() -> {
                    Coupon coupon = metrics.recordDb(strategy, () -> couponRepository.findById(couponId)
                            .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON)));
                    metrics.recordRedis(strategy, () -> {
                        couponIssuedRedisRepository.setTotalCount(couponId, coupon.getTotalCount());
                        return null;
                    });
                    return coupon.getTotalCount();
                });
    }
}
