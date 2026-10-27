package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class CouponNotFoundException extends BaseException{

    public CouponNotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }
}
