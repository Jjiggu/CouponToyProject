package couponToy.CouponToyProject.CouponIssue.dto;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.Member.model.Member;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IssueCouponRequest {

    private Long memberId;

    private Long couponId;


    public IssueCoupon toEntity(Member member, Coupon coupon) {
        return IssueCoupon.builder()
                .memberId(member.getMemberId())
                .couponId(coupon.getCouponId())
                .build();
    }

}
