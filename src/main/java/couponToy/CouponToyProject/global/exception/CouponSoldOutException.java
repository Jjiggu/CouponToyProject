package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class CouponSoldOutException extends RuntimeException{

    public CouponSoldOutException (ErrorCode errorCode) {
        super(errorCode.getMessage());
    }
}
