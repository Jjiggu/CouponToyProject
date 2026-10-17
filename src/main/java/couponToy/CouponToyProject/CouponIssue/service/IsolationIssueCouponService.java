package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1단계: 명시적 락 없이 격리 수준만 바꿔서 비교한다.
 * 두 public 메서드는 같은 로직을 쓰고 isolation 속성만 다르다.
 * (@Version, SELECT ... FOR UPDATE, Redis, 조건부 UPDATE 없음)
 */
@Service
@RequiredArgsConstructor
public class IsolationIssueCouponService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public IssueCouponResponse issueWithRepeatableRead(Long memberId, Long couponId) {
        return issue(memberId, couponId);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public IssueCouponResponse issueWithSerializable(Long memberId, Long couponId) {
        return issue(memberId, couponId);
    }

    // private 이므로 프록시를 거치지 않는다. 트랜잭션은 위 public 메서드가 다른 빈에서 호출될 때만 시작된다.
    private IssueCouponResponse issue(Long memberId, Long couponId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));

        // 조회: 잠금 없는 SELECT. SERIALIZABLE 에서는 InnoDB 가 이 SELECT 를 FOR SHARE(S 락)로 바꾼다.
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

        if (issueCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
            throw new IsAlreadyIssued(ErrorCode.IS_ALREADY_ISSUED);
        }

        // 수량 확인 + 엔티티 값 증가: 위에서 읽은 issuedCount 를 기준으로 Java 메모리에서 판단한다.
        coupon.increaseIssueAmount();

        // 저장: IDENTITY 라 INSERT 는 즉시 실행되고,
        // coupons UPDATE(issued_count = 계산된 값)는 커밋 직전 dirty checking flush 때 실행된다.
        IssueCoupon issueCoupon = issueCouponRepository.save(new IssueCoupon(member, coupon));
        return IssueCouponResponse.fromEntity(issueCoupon);
    }
}
