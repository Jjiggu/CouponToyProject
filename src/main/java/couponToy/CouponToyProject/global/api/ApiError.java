package couponToy.CouponToyProject.global.api;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiError {
    private final String message;
    private final int status;

    public ApiError(String message, int status) {
        this.message = message;
        this.status = status;
    }

    public ApiError(Throwable throwable, HttpStatus httpStatus) {
        this(throwable.getMessage(), httpStatus);
    }

    public ApiError (String message, HttpStatus httpStatus) {
        this(message, httpStatus.value());
    }
}
