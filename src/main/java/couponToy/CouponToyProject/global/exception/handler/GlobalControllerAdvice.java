package couponToy.CouponToyProject.global.exception.handler;

import couponToy.CouponToyProject.global.api.ApiUtils;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalControllerAdvice {

    private final MessageSource messageSource;

    @ExceptionHandler(IsAlreadyIssued.class)
    public ResponseEntity<?> handleAlreadyIssuedException(IsAlreadyIssued ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(
                        ApiUtils.error(ex.getMessage(), HttpStatus.CONFLICT)
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrityViolationException(DataIntegrityViolationException ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMostSpecificCause().getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(
                        ApiUtils.error(ErrorCode.UNIQUE_VIOLATION.getMessage(), HttpStatus.CONFLICT)
                );
    }

    // 데드락 희생자, 락 대기 시간 초과: 거절이 아니라 재시도하면 성공할 수도 있는 일시적 실패라서 503
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<?> handlePessimisticLockingFailureException(PessimisticLockingFailureException ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMostSpecificCause().getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        ApiUtils.error(ErrorCode.LOCK_FAILURE.getMessage(), HttpStatus.SERVICE_UNAVAILABLE)
                );
    }

    // 낙관적 락 버전 충돌: 데드락과 같이 재시도하면 성공할 수도 있는 일시적 실패라서 503
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLockingFailureException(OptimisticLockingFailureException ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        ApiUtils.error(ErrorCode.OPTIMISTIC_CONFLICT.getMessage(), HttpStatus.SERVICE_UNAVAILABLE)
                );
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> handleRequestParsingException(Exception ex) {
        log.error("handling : {}, message : {}", ex.getClass().toString(), ex.getMessage());

        return ResponseEntity
                .badRequest()
                .body(
                        ApiUtils.error(ErrorCode.PARAM_PARSING_MAPPING_ERROR.getMessage(), HttpStatus.BAD_REQUEST)
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

    // Spring 6.1 부터 매핑 없는 경로는 NoResourceFoundException 으로 온다.
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<?> handleNoHandlerFoundException(
            Exception ex
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex
    ) {
        String message = ErrorCode.BEAN_VALIDATION_ERROR.getMessage();
        if (ex.getBindingResult().getFieldError() != null
                && ex.getBindingResult().getFieldError().getDefaultMessage() != null) {
            message = ex.getBindingResult().getFieldError().getDefaultMessage();
        }

        log.error("handling {}, message : {}", ex.getClass().toString(), message);

        return ResponseEntity
                .badRequest()
                .body(
                        ApiUtils.error(message, HttpStatus.BAD_REQUEST)
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
