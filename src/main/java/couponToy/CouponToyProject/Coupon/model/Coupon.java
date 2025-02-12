package couponToy.CouponToyProject.Coupon.model;

import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "coupons")
@SuperBuilder
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


    @PrePersist
    public void prePersist() {
        if (this.issuedCount == null) {
            this.issuedCount = 0;
        }
    }


    public void increaseIssue() {this.issuedCount++;}

}
