package couponToy.CouponToyProject.Coupon.dto;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@NoArgsConstructor
@SuperBuilder
public class CouponReadRequest extends BaseTimeEntity {

    private Long couponId;

    public static CouponReadRequest toEntity(Coupon coupon) {
        return CouponReadRequest.builder()
                .couponId(coupon.getCouponId())
                .build();
    }
}
