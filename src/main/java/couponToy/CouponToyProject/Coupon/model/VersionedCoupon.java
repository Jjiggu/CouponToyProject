package couponToy.CouponToyProject.Coupon.model;

import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.entity.BaseTimeEntity;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 2단계 낙관적 락 전용 매핑. Coupon 과 같은 coupons 테이블을 읽고 쓰지만 version 컬럼을 검사한다.
 * Coupon 에 @Version 을 넣으면 1단계·비관적 락 구현까지 낙관적 락으로 바뀌므로 매핑을 분리했다.
 * 이 엔티티를 거치지 않는 쓰기(Coupon 엔티티, 벌크 UPDATE)는 version 을 올리지 않는다.
 */
@Entity
@Table(name = "coupons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VersionedCoupon extends BaseTimeEntity {

    // Coupon 과 컬럼 정의를 똑같이 맞춘다. 스키마 생성 시 두 매핑이 한 테이블로 합쳐진다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long couponId;

    @Column(nullable = false)
    private Integer totalCount;

    @Column(nullable = false)
    private Integer issuedCount;

    // Coupon 으로 INSERT 하면 이 컬럼을 모르므로 DB 기본값 0 이 들어간다.
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version;

    // Coupon.increaseIssueAmount 와 같은 규칙
    public void increaseIssueAmount() {
        if (issuedCount >= totalCount) {
            throw new CouponSoldOutException(ErrorCode.COUPON_SOLD_OUT);
        }
        issuedCount++;
    }
}
