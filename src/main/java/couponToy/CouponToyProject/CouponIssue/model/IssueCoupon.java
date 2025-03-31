package couponToy.CouponToyProject.CouponIssue.model;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
@Table(name = "coupon_issues")
public class IssueCoupon extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long issuedId;

    @ManyToOne
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

}
