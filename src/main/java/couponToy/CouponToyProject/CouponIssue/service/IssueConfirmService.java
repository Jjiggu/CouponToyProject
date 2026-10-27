package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponSoldOutAtDbException;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 로 발급 자격을 얻은 요청을 DB 에 확정한다. Redis 예약 방식과 상관없이 같은 저장 조건을 쓴다.
 * DB 쪽 마지막 방어선은 두 가지다.
 * - UNIQUE(coupon_id, member_id): 같은 회원의 두 번째 행을 막는다 (DataIntegrityViolationException)
 * - 조건부 UPDATE issued_count < total_count: 재고보다 많은 확정을 막는다 (CouponSoldOutAtDbException)
 */
@Service
@RequiredArgsConstructor
public class IssueConfirmService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;
    private final FailurePoints failurePoints;

    @Transactional
    public IssueCouponResponse confirm(Long memberId, Long couponId) {
        if (!memberRepository.existsById(memberId)) {
            throw new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER);
        }
        IssueCoupon issueCoupon = issueCouponRepository.save(
                IssueCoupon.builder().memberId(memberId).couponId(couponId).build());
        if (couponRepository.increaseIssuedCount(couponId) != 1) {
            throw new CouponSoldOutAtDbException();
        }
        failurePoints.reach(Point.DB_BEFORE_COMMIT, memberId);
        return IssueCouponResponse.fromEntity(issueCoupon);
    }
}
