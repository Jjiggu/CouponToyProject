package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class DuplicateMemberException extends BaseException {

    private ErrorCode errorCode;

    public DuplicateMemberException(ErrorCode errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }
}

