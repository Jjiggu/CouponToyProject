package couponToy.CouponToyProject.CouponIssue.event;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.CouponIssue.service.CouponIssueMetrics;
import couponToy.CouponToyProject.CouponIssue.service.CouponIssueStrategy;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueEventListener {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final CouponIssueMetrics metrics;

    @Async("couponIssueEventExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCouponIssued(CouponIssuedEvent event) {
        try {
            metrics.recordDb(CouponIssueStrategy.REDIS_EVENT, () -> {
                Member member = memberRepository.findById(event.memberId())
                        .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));
                Coupon coupon = couponRepository.findById(event.couponId())
                        .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

                issueCouponRepository.save(new IssueCoupon(member, coupon));
                int updated = couponRepository.increaseIssuedCount(event.couponId());
                if (updated != 1) {
                    throw new CouponSoldOutException(ErrorCode.COUPON_SOLD_OUT);
                }
                return null;
            });
            metrics.recordEventCompleted(Duration.between(event.requestedAt(), Instant.now()));
        } catch (Exception e) {
            couponIssuedRedisRepository.remove(event.couponId(), event.memberId());
            metrics.recordEventFailed(Duration.between(event.requestedAt(), Instant.now()));
            log.error("Async coupon issue failed. couponId={}, memberId={}, message={}",
                    event.couponId(), event.memberId(), e.getMessage(), e);
        }
    }
}
