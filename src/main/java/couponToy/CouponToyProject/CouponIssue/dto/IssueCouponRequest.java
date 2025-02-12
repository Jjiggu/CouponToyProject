package couponToy.CouponToyProject.CouponIssue.dto;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class IssueCouponRequest extends BaseTimeEntity {

    private Member member;

    private Coupon coupon;


    public IssueCoupon toEntity(Member member, Coupon coupon) {
        return IssueCoupon.builder()
                .member(member)
                .coupon(coupon)
                .build();
    }

}
