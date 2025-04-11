package couponToy.CouponToyProject.queue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponQueueSchedulerService {

    private final CouponRepository couponRepository;
    private final IssueCouponRepository issueCouponRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void issueFromQueue(Long couponId, Long userId) {
        // 1. 쿠폰을 비관적 락으로 조회
        Coupon coupon = couponRepository.findByCouponIdForUpdate(couponId)
                .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

        // 2. 발급 수량 초과 여부 확인 & 발급 수량 증가
        coupon.increaseIssueAmount();

        // 3. 발급 이력 저장
        IssueCoupon issueCoupon = IssueCoupon.builder()
                .memberId(userId)
                .couponId(couponId)
                .build();

        issueCouponRepository.save(issueCoupon);
    }
}
