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
    @ColumnDefault("0")
    private Integer totalCount;

    @Column(nullable = true)
    @ColumnDefault("0")
    private Integer issuedCount;


//    public Coupon(LocalDateTime regDate, LocalDateTime updateDate, Long couponId, String name, Integer totalCount, Integer issuedCount) {
//        super(regDate, updateDate);
//        this.couponId = couponId;
//        this.name = name;
//        this.totalCount = totalCount;
//        this.issuedCount = issuedCount;
//    }

}
