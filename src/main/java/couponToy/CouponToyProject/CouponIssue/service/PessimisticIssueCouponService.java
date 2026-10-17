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
import org.springframework.transaction.annotation.Transactional;

/**
 * 비관적 락 비교 구현. 회원 → 쿠폰 락 → 중복 확인 → 재고 증가 → 발급 이력 저장 순서로 처리한다.
 * 쿠폰 락과 DB 변경은 하나의 트랜잭션에 묶인다.
 */
@Service
@RequiredArgsConstructor
public class PessimisticIssueCouponService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;

    @Transactional
    public IssueCouponResponse issueCoupon(Long memberId, Long couponId) {
        // MySQL REPEATABLE READ에서는 첫 일반 SELECT가 스냅샷을 만든다. 이 조회가 쿠폰 락보다 먼저다.
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));

        // PESSIMISTIC_WRITE(SELECT ... FOR UPDATE): 같은 쿠폰의 다른 발급은 여기서 기다린다.
        // 락 읽기는 대기 후 최신 issuedCount를 읽고, 락은 트랜잭션 종료까지 유지된다.
        Coupon coupon = couponRepository.findByCouponIdForUpdate(couponId)
                .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

        // 위 회원 조회에서 만든 RR 스냅샷을 읽으므로, 락 대기 중 커밋된 발급 행을 못 볼 수 있다.
        // 같은 회원의 중복 발급은 coupon_issues의 UNIQUE(coupon_id, member_id)가 최종 방어한다.
        if (issueCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
            throw new IsAlreadyIssued(ErrorCode.IS_ALREADY_ISSUED);
        }

        // 쿠폰 행 락을 가진 상태에서 재고를 확인하고 메모리의 issuedCount를 증가시킨다.
        coupon.increaseIssueAmount();

        // IDENTITY 발급 이력 INSERT는 즉시 실행되고, 쿠폰 UPDATE는 flush 때 실행된다. 둘 다 같은 트랜잭션이다.
        IssueCoupon issueCoupon = issueCouponRepository.save(new IssueCoupon(member, coupon));
        return IssueCouponResponse.fromEntity(issueCoupon);
    }
}
