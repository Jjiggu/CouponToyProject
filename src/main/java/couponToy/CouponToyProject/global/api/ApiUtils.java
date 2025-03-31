package couponToy.CouponToyProject.global.api;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiUtils {
    public static <T> ApiResult<T> success(T response) {
        return new ApiResult<>(true, response, null);
    }

    public static ApiResult<?> error(Throwable throwable, HttpStatus httpStatus) {
        return new ApiResult<>(false, null, new ApiError(throwable, httpStatus));
    }

    public static ApiResult<?> error(String message, HttpStatus httpStatus) {
        return new ApiResult<>(false, null, new ApiError(message, httpStatus));
    }
}
