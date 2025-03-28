package couponToy.CouponToyProject.Coupon.dto;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CouponReadResponse extends BaseTimeEntity {

    @NotNull
    private Long couponId;

    @NotNull
    private String name;

    @NotNull
    private Integer totalCount;

    private Integer issuedCount;

    public static CouponReadResponse fromEntity(Coupon coupon) {
        return CouponReadResponse.builder()
                .couponId(coupon.getCouponId())
                .name(coupon.getName())
                .totalCount(coupon.getTotalCount())
                .issuedCount(coupon.getIssuedCount())
                .createdAt(coupon.getCreatedAt())
                .updatedAt(coupon.getUpdatedAt())
                .build();
    }
}
