package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class CouponSoldOutException extends RuntimeException{

    private final ErrorCode errorCode;

    public CouponSoldOutException (ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
