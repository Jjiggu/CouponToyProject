package couponToy.CouponToyProject.global.exception.handler;

import couponToy.CouponToyProject.global.api.ApiUtils;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.DuplicateMemberException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalControllerAdvice {

    private final MessageSource messageSource;

    @ExceptionHandler({DuplicateMemberException.class, IsAlreadyIssued.class})
    public ResponseEntity<?> handleBadRequestException(RuntimeException ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .badRequest()
                .body(
                        ApiUtils.error(ex.getMessage(), HttpStatus.BAD_REQUEST)
                );
    }

    @ExceptionHandler(CouponSoldOutException.class)
    public ResponseEntity<?> handleCouponSoldOutException(CouponSoldOutException ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(
                        ApiUtils.error(ex.getMessage(), HttpStatus.CONFLICT)
                );
    }

    @ExceptionHandler({CouponNotFoundException.class, MemberNotFoundException.class})
    public ResponseEntity<?> handleNotFoundException(RuntimeException ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(
                        ApiUtils.error(ex.getMessage(), HttpStatus.NOT_FOUND)
                );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> handleAuthenticationException(
            AuthenticationException ex
    ) {
        log.error("handling {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(
                        ApiUtils.error(ErrorCode.UNAUTHORIZED.getMessage(),HttpStatus.UNAUTHORIZED)
                );
    }


    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<?> handleNoHandlerFoundException(
            NoHandlerFoundException ex
    ) {
        log.error("handling {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(
                        ApiUtils.error(ErrorCode.NO_HANDLER_FOUND.getMessage(),HttpStatus.NOT_FOUND)
                );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex
    ) {
        log.error("handling {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(
                        ApiUtils.error(ErrorCode.HTTP_REQUEST_METHOD_NOT_SUPPORTED.getMessage(),HttpStatus.METHOD_NOT_ALLOWED)
                );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(
            Exception ex
    ) {
        log.error("handling {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .internalServerError()
                .body(
                        ApiUtils.error(ErrorCode.INTERNAL_SERVER_ERROR.getMessage(),HttpStatus.INTERNAL_SERVER_ERROR)
                );
    }


}
