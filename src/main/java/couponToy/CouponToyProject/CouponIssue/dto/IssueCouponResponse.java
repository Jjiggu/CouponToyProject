package couponToy.CouponToyProject.CouponIssue.dto;

import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@NoArgsConstructor
@SuperBuilder
public class IssueCouponResponse extends BaseTimeEntity {

    @NotNull
    private Long issuedId;

    @NotNull
    private Long memberId;

    @NotNull
    private Long couponId;


    public static IssueCouponResponse fromEntity(IssueCoupon couponIssue) {
        return IssueCouponResponse.builder()
                .createdAt(couponIssue.getCreatedAt())
                .issuedId(couponIssue.getIssuedId())
                .memberId(couponIssue.getMemberId())
                .couponId(couponIssue.getCouponId())
                .build();

    }
}
