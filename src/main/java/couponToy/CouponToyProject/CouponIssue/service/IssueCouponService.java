package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponRequest;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import couponToy.CouponToyProject.global.security.MemberDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueCouponService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;

    @Transactional
    public IssueCouponResponse issueCoupon(IssueCouponRequest issueCouponRequest, MemberDetails memberDetails, Long couponId) {
        Member member = memberRepository.findById(memberDetails.getMemberId()).orElseThrow(
                () -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER)
        );

        Coupon coupon = couponRepository.findByCouponIdForUpdate(couponId)
                .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON)
        );

        coupon.increaseIssueAmount();
        IssueCoupon issueCoupon = issueCouponRepository.save(issueCouponRequest.toEntity(member, coupon));

        return IssueCouponResponse.fromEntity(issueCoupon);
    }


    @Transactional
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

