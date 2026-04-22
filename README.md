# 선착순 쿠폰 발급 시스템

고동시성 환경에서 선착순 쿠폰을 정확하게 발급하는 토이 프로젝트.

**핵심 불변식**
- 지급 수량을 절대 초과하지 않는다
- 동일 사용자에게 1장만 발급한다

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.4.1, Spring Security, Spring Data JPA, Spring Data Redis |
| Database | MySQL 8 |
| Cache | Redis |
| Auth | JWT (`com.auth0:java-jwt`) |
| Test | JUnit 5 |

## 실행 방법

**인프라 실행**

```bash
# MySQL (localhost:3307)
docker-compose -f src/main/resources/docker-compose.yml up -d

# Redis (localhost:6379) — 별도 실행
```

**애플리케이션 실행**

```bash
./gradlew bootRun
```

**테스트** (MySQL, Redis 실행 필요)

```bash
./gradlew test
./gradlew test --tests "*.IssueCouponServiceTest"
```

## API

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/members/signup` | 불필요 | 회원가입 |
| POST | `/members/login` | 불필요 | 로그인 → JWT 반환 |
| POST | `/api/coupons` | 필요 | 쿠폰 생성 |
| GET | `/api/coupons/{couponId}` | 필요 | 쿠폰 조회 |
| POST | `/coupons/issue/{couponId}` | 필요 | 쿠폰 발급 |

인증이 필요한 요청은 `Authorization: Bearer {token}` 헤더를 포함한다.

모든 응답은 아래 형태로 반환된다.

```json
{ "success": true,  "response": { }, "error": null }
{ "success": false, "response": null, "error": { "message": "...", "status": 409 } }
```

## 쿠폰 발급 플로우

```
POST /coupons/issue/{couponId}

  [1] Redis GET coupon:total:{couponId}          totalCount 캐시 조회
      └─ 캐시 미스 → DB 조회 후 재캐싱 (자가 복구)

  [2] Redis MULTI/EXEC
      SCARD coupon:issued:{couponId}             현재 발급 수
      SADD  coupon:issued:{couponId}  {userId}   중복 등록 시도
      ─────────────────────────────────────────
      SADD == 0          → 400 IsAlreadyIssued   (DB 미접근)
      SCARD >= totalCount → 409 CouponSoldOut    (SREM 후 예외)

  [3] SELECT ... FOR UPDATE (PESSIMISTIC_WRITE)
      coupon.increaseIssueAmount()               DB 최종 수량 검증
      coupon_issues INSERT

  [4] 예외 발생 시 → SREM (Redis 롤백)
```

## Redis 키

| 키 | 자료형 | 설명 |
|---|---|---|
| `coupon:total:{couponId}` | String | 총 발급 수량 (쿠폰 생성 시 웜업, 불변) |
| `coupon:issued:{couponId}` | SET | 발급된 userId 집합 |

## 문서

상세 플로우는 [`docs/`](./docs/) 디렉토리를 참고한다.

- [docs/overview.md](./docs/overview.md) — 시스템 개요, DB 스키마
- [docs/auth.md](./docs/auth.md) — 인증 플로우
- [docs/coupon-issue.md](./docs/coupon-issue.md) — 쿠폰 발급 플로우 상세
- [docs/error-handling.md](./docs/error-handling.md) — 예외 처리
