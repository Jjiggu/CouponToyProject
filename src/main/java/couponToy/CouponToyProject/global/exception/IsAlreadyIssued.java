package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class IsAlreadyIssued extends BaseException{
    public IsAlreadyIssued(ErrorCode errorCode) {
        super(errorCode);
    }
}
