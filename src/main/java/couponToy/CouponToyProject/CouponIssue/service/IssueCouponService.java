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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        Coupon coupon = couponRepository.findById(couponId).orElseThrow(
                () -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON)
        );

        coupon.increaseIssueAmount();
        IssueCoupon issueCoupon = issueCouponRepository.save(issueCouponRequest.toEntity(member, coupon));

        return IssueCouponResponse.fromEntity(issueCoupon);
    }
}

