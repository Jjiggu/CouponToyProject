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

기본 설정은 [`src/main/resources/application.yml`](./src/main/resources/application.yml) 에 들어 있다.

- MySQL: `localhost:3306`
- Redis: `localhost:6379`
- DB 이름: `CouponToyProject`
- DB 계정: 로컬 `root`

`src/test/resources/application.yml` 은 분리된 `CouponToyProject_test` DB를 바라보도록 잡아뒀다.

**인프라 실행**
Docker daemon 이 켜져 있어야 한다.

```bash
docker compose up -d mysql redis
```

스프링 서버, Prometheus, Grafana까지 같은 제한 자원으로 올리려면 아래처럼 실행한다.

```bash
docker compose up -d --build
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

`k6` 성능 테스트처럼 재현 가능한 환경이 필요하면 Compose로 MySQL, Redis, 앱을 함께 띄우는 방식을 권장한다.

## API

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/members/signup` | 불필요 | 회원가입 |
| POST | `/members/login` | 불필요 | 로그인 → JWT 반환 |
| POST | `/coupons` | 필요 | 쿠폰 생성 |
| GET | `/coupons/{couponId}` | 필요 | 쿠폰 조회 |
| POST | `/coupons/issue/{couponId}` | 필요 | 기존 Redis Transactional 쿠폰 발급 |
| POST | `/coupons/issue/pessimistic/{couponId}` | 필요 | 기존 비교용 비관적 락 발급 |
| POST | `/coupons/issue/{couponId}/pessimistic` | 필요 | 성능 테스트용 비관적 락 발급 |
| POST | `/coupons/issue/{couponId}/redis-transactional` | 필요 | 성능 테스트용 Redis Transactional 발급 |
| POST | `/coupons/issue/{couponId}/redis-event` | 필요 | 성능 테스트용 Redis + Spring Event 비동기 발급 |

인증이 필요한 요청은 `Authorization: Bearer {token}` 헤더를 포함한다.
`POST /coupons/issue/{couponId}` 는 요청 바디 없이 호출할 수 있다.

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
      SADD == 0          → 409 IsAlreadyIssued   (DB 미접근)
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
- [docs/test-guide.md](./docs/test-guide.md) — 테스트 실행 순서와 결과 확인 방법
- [docs/performance/coupon-issue-comparison.md](./docs/performance/coupon-issue-comparison.md) — `k6` 성능 비교 가이드
- [docs/performance/monitoring-guide.md](./docs/performance/monitoring-guide.md) — Grafana/Prometheus 모니터링 가이드
- [docs/performance/max-tps-guide.md](./docs/performance/max-tps-guide.md) — 최대 TPS 측정 가이드
- [docs/performance/max-tps-results.md](./docs/performance/max-tps-results.md) — 최대 TPS 측정 결과와 해석

## Coupon Performance Test Docs

쿠폰 발급 성능/부하 테스트 문서는 [docs/coupon-performance-test](docs/coupon-performance-test/README.md)에 정리되어 있습니다.
