package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;

public class DuplicateMemberException extends BaseException {

    public DuplicateMemberException(ErrorCode errorCode) {
        super(errorCode);
    }
}

