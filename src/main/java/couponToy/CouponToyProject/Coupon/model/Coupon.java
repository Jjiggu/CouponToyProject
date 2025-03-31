package couponToy.CouponToyProject.Coupon.model;

import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "coupons")
@SuperBuilder
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Coupon extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long couponId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer totalCount;

    @Column(nullable = false)
    private Integer issuedCount = 0;

    @Version
    @Column(name = "version")
    private Long version;

    public void increaseIssueAmount() {
        if (issuedCount >= totalCount) {
            throw new CouponSoldOutException(ErrorCode.COUPON_SOLD_OUT);
        }
        issuedCount++;
    }

}
