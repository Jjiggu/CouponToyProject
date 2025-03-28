package couponToy.CouponToyProject.Coupon.dto;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CouponCreateRequest extends BaseTimeEntity {

    @NotNull
    private String name;

    @NotNull
    private Integer totalCount;

    public Coupon toEntity() {
        return Coupon.builder()
                .name(name)
                .totalCount(totalCount)
                .build();
    }
}
