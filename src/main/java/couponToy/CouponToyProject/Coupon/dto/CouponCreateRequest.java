package couponToy.CouponToyProject.Coupon.dto;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CouponCreateRequest {

    @NotNull
    private String name;

    @NotNull
    @Positive
    private Integer totalCount;

    public Coupon toEntity() {
        return Coupon.builder()
                .name(name)
                .totalCount(totalCount)
                .issuedCount(0)
                .build();
    }
}
