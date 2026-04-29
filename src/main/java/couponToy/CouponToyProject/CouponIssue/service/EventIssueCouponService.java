package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.event.CouponIssuedEvent;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.security.MemberDetails;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventIssueCouponService {

    private final CouponRepository couponRepository;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public IssueCouponResponse issueCoupon(MemberDetails memberDetails, Long couponId) {
        Long userId = memberDetails.getMemberId();

        // [1] totalCount 조회: Redis 캐시 우선, 캐시 미스 시 DB 폴백 후 재캐싱
        int totalCount = couponIssuedRedisRepository.getTotalCount(couponId)
                .orElseGet(() -> {
                    Coupon c = couponRepository.findById(couponId)
                            .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));
                    couponIssuedRedisRepository.setTotalCount(couponId, c.getTotalCount());
                    return c.getTotalCount();
                });

        // [2] Redis MULTI/EXEC: 중복 방지 + 수량 사전 체크
        couponIssuedRedisRepository.checkAndRegister(couponId, userId, totalCount);

        // [3] 비동기 이벤트로 DB 발급 내역 저장 위임 (비관적 락 없음)
        eventPublisher.publishEvent(new CouponIssuedEvent(userId, couponId));

        return IssueCouponResponse.builder()
                .memberId(userId)
                .couponId(couponId)
                .build();
    }
}
