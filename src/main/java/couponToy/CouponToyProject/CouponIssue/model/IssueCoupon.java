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
@Table(
        name = "coupon_issues",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_coupon_issue_coupon_member", columnNames = {"coupon_id", "member_id"})
        }
)
public class IssueCoupon extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long issuedId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    public IssueCoupon(Member member, Coupon coupon) {
        this.memberId = member.getMemberId();
        this.couponId = coupon.getCouponId();
    }
}
