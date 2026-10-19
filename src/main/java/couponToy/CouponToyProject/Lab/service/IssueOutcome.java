package couponToy.CouponToyProject.Lab.service;

import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutAtDbException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.sql.SQLException;

/**
 * 실험 진입점에서 관찰한 요청 하나의 결과.
 * 거절(SOLD_OUT, DUPLICATE, UNIQUE_VIOLATION)은 재시도해도 바뀌지 않는 답이고,
 * 실패(DEADLOCK, LOCK_WAIT_TIMEOUT, OPTIMISTIC_CONFLICT)는 같은 요청이 다시 시도되면 성공할 수도 있는 트랜잭션 실패다.
 */
public enum IssueOutcome {
    SUCCESS,            // DB 커밋까지 끝난 발급
    ACCEPTED,           // 접수만 됨 (비동기). 발급 완료가 아니다.
    SOLD_OUT,
    SOLD_OUT_AT_DB,     // Redis 예약은 통과했지만 DB 조건부 UPDATE 가 막은 품절 (Redis 와 DB 가 어긋남)
    DUPLICATE,          // 애플리케이션 중복 확인이 막음
    UNIQUE_VIOLATION,   // 애플리케이션 확인은 통과했지만 DB UNIQUE 제약이 막음
    DEADLOCK,           // MySQL 1213: 데드락 희생자로 롤백
    LOCK_WAIT_TIMEOUT,  // MySQL 1205: innodb_lock_wait_timeout 초과
    OPTIMISTIC_CONFLICT, // 커밋 시 version 검사 실패 (재시도가 있으면 재시도를 모두 쓴 뒤)
    NOT_FOUND,
    ERROR;

    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

    public static IssueOutcome of(Throwable e) {
        if (e instanceof CouponSoldOutAtDbException) return SOLD_OUT_AT_DB;
        if (e instanceof CouponSoldOutException) return SOLD_OUT;
        if (e instanceof IsAlreadyIssued) return DUPLICATE;
        if (e instanceof CouponNotFoundException || e instanceof MemberNotFoundException) return NOT_FOUND;
        if (e instanceof DataIntegrityViolationException) return UNIQUE_VIOLATION;
        if (e instanceof OptimisticLockingFailureException) return OPTIMISTIC_CONFLICT;
        // Spring 이 어떤 예외로 감싸든 원인 체인의 MySQL 에러 코드로 판단한다.
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == MYSQL_DEADLOCK) return DEADLOCK;
                if (sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT) return LOCK_WAIT_TIMEOUT;
            }
        }
        return ERROR;
    }
}
