package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

/**
 * Redis 예약은 통과했지만 DB 조건부 UPDATE(issued_count < total_count)가 막은 품절.
 * 사용자에게는 같은 품절이지만, Redis 와 DB 의 수량 판단이 어긋났다는 뜻이다.
 */
public class CouponSoldOutAtDbException extends CouponSoldOutException {

    public CouponSoldOutAtDbException() {
        super(ErrorCode.COUPON_SOLD_OUT);
    }
}
