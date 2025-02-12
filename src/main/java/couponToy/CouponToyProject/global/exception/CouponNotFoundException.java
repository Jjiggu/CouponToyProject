package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class CouponNotFoundException extends BaseException{

    private final ErrorCode errorCode;

    public CouponNotFoundException(ErrorCode errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }
}
