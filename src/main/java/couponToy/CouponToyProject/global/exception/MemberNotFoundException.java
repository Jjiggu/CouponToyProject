package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class MemberNotFoundException extends BaseException{

    public MemberNotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }
}
