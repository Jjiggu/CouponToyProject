# 선착순 쿠폰 발급 시스템

고동시성 환경에서 선착순 쿠폰을 정확하게 발급하는 토이 프로젝트.

**핵심 불변식**
- 지급 수량을 절대 초과하지 않는다
- 동일 사용자에게 1장만 발급한다

## 학습 단계

동시성 제어와 비동기 처리를 한 단계씩 구현하고 직접 실행해 확인한다. 계획, 실험 API(`/lab`, `local` 프로필 전용), 단계별 결과는 [docs/learning](docs/learning/README.md) 에 있다.

- [1단계: 명시적 락 없이 격리 수준 비교](docs/learning/01-isolation-level.md)
- [2단계: 비관적 락과 낙관적 락](docs/learning/02-optimistic-pessimistic.md)
- [3단계: Redis 예약 — SET 명령만 / MULTI/EXEC / WATCH / Lua](docs/learning/03-redis-transaction-lua.md)

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.4.1, Spring Data JPA, Spring Data Redis |
| Database | MySQL 8.0.36 |
| Cache | Redis 7.2 |
| Test | JUnit 5, k6 |

## 실행 방법

기본 설정은 `src/main/resources/application.yml`(git 미추적) 에 있다. 기본 프로필은 `local` 이다.

- MySQL: `localhost:3306`, DB `CouponToyProject`
- Redis: `localhost:6379`
- 테스트: `CouponToyProject_test` DB(create-drop), Redis db 1

```bash
docker compose up -d mysql redis
./gradlew bootRun
./gradlew test
```

`DELETE /lab/data` 는 연결된 DB 의 쿠폰·발급·회원 테이블을 TRUNCATE 한다. 기존 데이터를 남기려면 별도 스키마로 띄운다.

```bash
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
SPRING_DATA_REDIS_DATABASE=2 ./gradlew bootRun
```

## API (`local` 프로필 전용, 인증 없음)

| Method | Path | Body | 설명 |
|---|---|---|---|
| POST | `/lab/members` | `{"count": 1000}` | 실험용 회원 일괄 생성 |
| POST | `/lab/coupons` | `{"name": "s1", "totalCount": 100}` | 실험용 쿠폰 생성 |
| POST | `/lab/coupons/{couponId}/issue` | `{"memberId": 1, "method": "SERIALIZABLE"}` | 방식 선택 발급: `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE`, `PESSIMISTIC`, `OPTIMISTIC`, `OPTIMISTIC_RETRY`, `REDIS_SET_ONLY`, `REDIS_MULTI_EXEC`, `REDIS_WATCH`, `REDIS_LUA`, `REDIS_ASYNC`·`REDIS_PUBSUB`(202 접수). 격리 수준 방식은 `duplicateCheck: false` 가능 |
| GET | `/lab/coupons/{couponId}/result` | | 발급 결과, 수량, 검증 기준, 순서, 지연 |
| DELETE | `/lab/data` | | 실험 데이터 초기화 |

모든 응답은 아래 형태로 반환된다. 품절·중복은 409, 데드락·락 대기 초과·비동기 큐 포화는 503, 잘못된 입력은 400.

```json
{ "success": true,  "response": { }, "error": null }
{ "success": false, "response": null, "error": { "message": "...", "status": 409 } }
```

## Redis 발급 플로우 (3단계)

```
RedisIssueCouponService.issue(memberId, couponId, method)   ← DB 트랜잭션 없음

  [0] GET coupon:total:{couponId}                총수량 (없으면 DB 에서 읽어 다시 넣음)
  [1] Redis 예약 (CouponIssuedRedisRepository)     "이미 예약됨 → 재고 소진 → 예약" 순서, 원자성만 방식별로 다름
        REDIS_SET_ONLY    SISMEMBER, SCARD, SADD 를 따로 보냄 (명령 사이에 다른 요청이 끼어든다)
        REDIS_MULTI_EXEC  MULTI SCARD SADD EXEC → Java 판단 → 재고 초과면 SREM
        REDIS_WATCH       WATCH → 읽기 → MULTI SADD EXEC (키가 바뀌었으면 다시, 최대 10회)
        REDIS_LUA         EVALSHA 한 번 (4~6단계 공통 기준)
  [2] IssueConfirmService.confirm() — 별도 DB 트랜잭션
        INSERT coupon_issues                     UNIQUE(coupon_id, member_id)
        UPDATE coupons SET issued_count = issued_count + 1
         WHERE coupon_id = ? AND issued_count < total_count   0건이면 SOLD_OUT_AT_DB
  [3] [2] 실패 시 SREM 으로 예약을 되돌린다. UNIQUE 위반(이미 발급된 회원)은 되돌리지 않는다
```

## 비동기 발급 플로우 (4단계, REDIS_ASYNC)

```
AsyncIssueCouponService.issue(memberId, couponId)        ← 요청 스레드
  [1] RedisIssueCouponService.reserve(LUA)                3단계와 같은 예약
  [2] RedisIssueCouponService.confirmLater()  @Async     couponIssueExecutor 큐에 넣고 바로 반환 → HTTP 202 (접수)
        큐가 가득 차면 TaskRejectedException → SREM, HTTP 503
작업 스레드 (스레드 풀 couponIssueExecutor: 기본 4개, 큐 50000, JVM 메모리)
  [3] IssueConfirmService.confirm(), 실패 시 SREM          3단계와 같은 확정·보상
결과 API: asyncResults(접수된 요청의 확정 결과), pending(접수됐지만 확정 결과가 아직 없는 요청)
```

## Pub/Sub 발급 플로우 (5단계, REDIS_PUBSUB)

```
발행자 프로세스 (local)        PubSubIssueCouponService.issue()      ← 요청 스레드
  [1] RedisIssueCouponService.reserve(LUA)                          3단계와 같은 예약
  [2] PUBLISH coupon:issue:requests "memberId:couponId" → HTTP 202 (PUBLISHED)
        돌려받는 "받은 구독자 수" 는 완료가 아니다. 구독자가 없으면 0 이고 메시지는 사라진다
구독자 프로세스 (pubsub-consumer)   PubSubIssueConsumer → 작업 스레드(couponIssueExecutor)
  [3] IssueConfirmService.confirm(), 실패 시 SREM                  3단계와 같은 확정·보상
결과 API: publishReceivers(받은 구독자 수별 요청 수), redisOnlyMembers(예약만 있고 확정되지 않은 회원)
```

## Redis 키

| 키 | 자료형 | 설명 |
|---|---|---|
| `coupon:total:{couponId}` | String | 총 발급 수량 (쿠폰 생성 시 웜업, 불변) |
| `coupon:issued:{couponId}` | SET | 발급 자격을 예약한 회원 집합 (DB 확정 전 예약도 들어 있다) |

## 이전 문서 (보존용)

학습 정리 이전 구조(JWT 인증, 이전 엔드포인트) 기준 문서와 결과다. 현재 코드와 다를 수 있다.

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
