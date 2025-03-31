package couponToy.CouponToyProject.global.exception;

import couponToy.CouponToyProject.global.constant.ErrorCode;
import lombok.Getter;

@Getter
public class BaseException extends RuntimeException{

    private final ErrorCode errorCode;

    public BaseException (ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
