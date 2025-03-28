package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class MemberNotFoundException extends BaseException{

    private final ErrorCode errorCode;

    public MemberNotFoundException(ErrorCode errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }
}
