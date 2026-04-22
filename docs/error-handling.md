# 예외 처리

---

## 예외 클래스 계층

```
RuntimeException
├── BaseException  (추상 — ErrorCode를 생성자로 받아 메시지 관리)
│   ├── CouponNotFoundException     쿠폰 없음
│   ├── MemberNotFoundException     회원 없음
│   ├── DuplicateMemberException    이메일 중복 가입
│   └── IsAlreadyIssued             이미 발급받은 사용자
│
└── CouponSoldOutException          쿠폰 수량 소진
    (BaseException 미상속, ErrorCode는 동일하게 사용)
```

---

## ErrorCode 목록

| 코드 | 메시지 | 발생 지점 |
|---|---|---|
| `DUPLICATE_EMAIL` | 이미 존재하는 이메일입니다 | 회원가입 이메일 중복 |
| `NOT_FOUND_MEMBER` | 일치하는 회원정보가 없습니다. | 발급 시 회원 조회 실패 |
| `NOT_FOUND_COUPON` | 일치하는 쿠폰정보가 없습니다. 쿠폰 번호를 확인해주세요 | 발급 시 쿠폰 조회 실패 |
| `COUPON_SOLD_OUT` | 쿠폰이 모두 소진되었습니다. | Redis 수량 초과 또는 DB increaseIssueAmount |
| `IS_ALREADY_ISSUED` | 이미 쿠폰 발급 받은 사용자입니다. | Redis SADD 결과 0 |
| `UNAUTHORIZED` | 인증 오류 | JWT 없음 / 만료 / 검증 실패 |
| `NO_HANDLER_FOUND` | 존재하지 않는 URL | 잘못된 경로 |
| `HTTP_REQUEST_METHOD_NOT_SUPPORTED` | 지원되지 않는 요청 메소드 | 잘못된 HTTP 메서드 |
| `INTERNAL_SERVER_ERROR` | Internal Server Error | 그 외 모든 예외 |

---

## GlobalControllerAdvice — HTTP 응답 매핑

`@RestControllerAdvice` 가 모든 컨트롤러 예외를 가로채 `ApiResult` 형태로 변환한다.

| 예외 클래스 | HTTP 상태 | 이유 |
|---|---|---|
| `DuplicateMemberException` | 400 Bad Request | 클라이언트 입력 오류 |
| `IsAlreadyIssued` | 400 Bad Request | 클라이언트 요청 오류 |
| `CouponSoldOutException` | 409 Conflict | 선점 충돌 |
| `CouponNotFoundException` | 404 Not Found | 리소스 없음 |
| `MemberNotFoundException` | 404 Not Found | 리소스 없음 |
| `AuthenticationException` | 401 Unauthorized | 인증 실패 |
| `NoHandlerFoundException` | 404 Not Found | 잘못된 URL |
| `HttpRequestMethodNotSupportedException` | 405 Method Not Allowed | 잘못된 HTTP 메서드 |
| `Exception` (catch-all) | 500 Internal Server Error | 예상치 못한 서버 오류 |

---

## 응답 예시

**중복 발급 (400)**
```json
{
  "success": false,
  "response": null,
  "error": { "message": "이미 쿠폰 발급 받은 사용자입니다.", "status": 400 }
}
```

**수량 소진 (409)**
```json
{
  "success": false,
  "response": null,
  "error": { "message": "쿠폰이 모두 소진되었습니다.", "status": 409 }
}
```

**인증 없음 (401)**
```json
{
  "success": false,
  "response": null,
  "error": { "message": "인증 오류", "status": 401 }
}
```

---

## @Retry AOP — 낙관적 락 재시도

현재 쿠폰 발급은 비관적 락을 사용하므로 `@Retry` 가 직접 적용되지는 않는다.  
낙관적 락 방식이 필요한 로직을 추가할 때 사용할 수 있도록 구성되어 있다.

**동작 방식**

```java
@Retry
@Transactional
public void someMethod() { ... }
```

```
OptimisticLockRetryAspect.retryOptimisticLock()
  최대 1,000회 재시도, 시도 간 100ms 대기
  감지 예외: OptimisticLockException
            ObjectOptimisticLockingFailureException
            StaleObjectStateException
  1,000회 초과 시 마지막 예외를 그대로 throw
```

`@Order(Ordered.LOWEST_PRECEDENCE - 1)` 로 `@Transactional` 보다 먼저 적용된다.  
예외 발생 시 트랜잭션이 롤백된 뒤 새 트랜잭션으로 재시도하는 순서가 보장된다.
