package couponToy.CouponToyProject.global.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 기존 Exception 에 대한 ErrorCode
    NO_HANDLER_FOUND("존재하지 않는 URL"),
    HTTP_REQUEST_METHOD_NOT_SUPPORTED("지원되지 않는 요청 메소드"),

    METHOD_ARGUMENT_TYPE_MISMATCH("요청 파라미터 오류"),
    PARAM_PARSING_MAPPING_ERROR("요청 입력 파싱 또는 매핑 오류"),
    BEAN_VALIDATION_ERROR("입력 데이터 유효성 검증 오류"),

    INTERNAL_SERVER_ERROR("Internal Server Error"),


    // Member
    NOT_FOUND_MEMBER("일치하는 회원정보가 없습니다."),

    // Coupon
    NOT_FOUND_COUPON("일치하는 쿠폰정보가 없습니다. 쿠폰 번호를 확인해주세요"),
    COUPON_SOLD_OUT("쿠폰이 모두 소진되었습니다."),


    // Queue
    IS_ALREADY_ISSUED("이미 쿠폰 발급 받은 사용자입니다."),

    // DB 가 막은 경우
    UNIQUE_VIOLATION("이미 쿠폰 발급 받은 사용자입니다. (DB UNIQUE 제약 위반)"),
    LOCK_FAILURE("DB 락 충돌로 트랜잭션이 롤백되었습니다. (데드락 또는 락 대기 시간 초과)");

    private final String message;
}
