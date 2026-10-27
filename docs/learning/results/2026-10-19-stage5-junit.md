# 5단계 JUnit 실행 원본 (2026-10-19): Redis Pub/Sub

`./gradlew test --tests '*PubSubIssueTest'` 가 `build/lab/stage5-pubsub.md` 에 남긴 내용을 옮겼다.

실행 조건
- Redis 7.2.13 (Docker, AOF·RDB 끔), 테스트는 db 1. Pub/Sub 채널 `coupon:issue:requests` 는 DB 번호와 관계없이 서버 전체에서 하나다(실행 중 다른 구독자 프로세스는 없었다)
- MySQL 8.0.36 (Docker, 1 CPU / 1GB), REPEATABLE READ, DB `CouponToyProject_test`
- 테스트 JVM: macOS 호스트, OpenJDK 17.0.19, Hikari 최대 10, Lettuce 기본 설정
- 구독자는 테스트가 `RedisMessageListenerContainer` 를 직접 만들어 띄운다(구독자 수만큼). 받은 메시지는 스레드 풀 `couponIssueExecutor`(작업 스레드 4)가 처리한다. 발행자와 구독자가 같은 JVM 이지만 Redis 연결은 따로다
- 구독자 수는 `PUBSUB NUMSUB` 로 확인한 뒤 진행했다. 처리 완료는 구독자 처리 결과 수(`PubSubIssueConsumer.results`)가 기대한 만큼 쌓이고 작업 스레드가 모두 쉴 때까지 10ms 간격으로 기다렸다
- 전체 테스트(135개, 그중 2개는 MySQL 전역 설정 실험이라 기본 실행에서 건너뜀)를 1번, 5단계 테스트를 2번 더 실행했다. 순서 고정 결과는 3번 모두 같았다(회원 번호만 다름). 아래는 1회차 기록이고, 동시 요청 표는 2·3회차도 붙였다

---

## 구독자 1개, 정상 전달 (재고 1)

- A 요청: PUBLISHED, PUBLISH 가 돌려준 구독자 수 {1=1}. 구독자는 `coupon-issue-async-1` 에서 확정
- 구독자 처리 결과 {SUCCESS=1}, DB 발급 행 1, Redis 에만 있는 회원 0

## 구독자가 받은 뒤 DB 확정 실패 (재고 1)

- A 요청: PUBLISHED → 구독자 처리 결과 {DataAccessResourceFailureException=1}, DB 발급 행 0, Redis 에만 있는 회원 0 (구독자가 보상 SREM)
- A 재요청: PUBLISHED → 구독자 처리 결과 누적 {DataAccessResourceFailureException=1, SUCCESS=1}, DB 발급 행 1

## 구독자 없음, 그 뒤 구독 (재고 1)

- 구독자 0 에서 A 요청: PUBLISHED, PUBLISH 가 돌려준 구독자 수 {0=1}
- 구독자를 붙이고 다른 쿠폰 메시지가 처리된 뒤: DB 발급 행 0, Redis 에만 있는 회원 1 (A 의 메시지는 오지 않음)
- A 재요청 DUPLICATE, B 요청 SOLD_OUT

## 구독자 2개, 그중 하나가 DB 확정 실패 (재고 1)

- A 요청: PUBLISHED → 구독자 처리 결과 {DataAccessResourceFailureException=1, SUCCESS=1}. DB 발급 행 1, Redis [] (DB 에만 있는 회원 1)
- B 요청: PUBLISHED → 구독자 처리 결과 누적 {CouponSoldOutAtDbException=2, DataAccessResourceFailureException=1, SUCCESS=1}. DB 발급 행 1, Redis []
- A 재요청: PUBLISHED → 구독자 처리 결과 누적 {CouponSoldOutAtDbException=2, DataAccessResourceFailureException=1, DataIntegrityViolationException=2, SUCCESS=1}. DB 발급 행 1, Redis [2434]

## 구독자 2개 (재고 1)

- A 요청: PUBLISHED, PUBLISH 가 돌려준 구독자 수 {2=1}
- 구독자 처리 결과 {DataIntegrityViolationException=1, SUCCESS=1}, DB 발급 행 1, issued_count 1, Redis [2436]

## 구독자가 받은 뒤 처리 전에 종료 흉내 (재고 1)

- A 요청: PUBLISHED, PUBLISH 가 돌려준 구독자 수 {1=1}. 구독자 작업 스레드에서 확정 전에 Error 를 던짐(보상 실행 안 됨)
- 구독자 처리 결과 {}, DB 발급 행 0, Redis 에만 있는 회원 1
- A 재요청 DUPLICATE, B 요청 SOLD_OUT


## 동시 요청: REDIS_PUBSUB, 구독자 1, 재고보다 많은 요청 (재고 10, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 2/4/4 | 18 | 124 |
| 2 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 5/10 | 4/6/6 | 15 | 125 |
| 3 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 8/10 | 3/4/4 | 9 | 128 |

## 동시 요청: REDIS_PUBSUB, 구독자 1, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 4/5/5 | 13 | 210 |
| 2 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 4/5/5 | 11 | 235 |
| 3 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 3/4/4 | 9 | 178 |

## 동시 요청: REDIS_PUBSUB, 구독자 1, 동일 회원 중복 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 4/5/5 | 12 | 127 |
| 2 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 2/2/3 | 10 | 123 |
| 3 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 3/4/4 | 14 | 136 |

## 동시 요청: REDIS_PUBSUB, 구독자 2, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/6/7 | 15 | 253 |
| 2 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/8/8 | 18 | 282 |
| 3 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 8/11/12 | 20 | 222 |

---

## 2회차 실행: 동시 요청
### 동시 요청: REDIS_PUBSUB, 구독자 1, 재고보다 많은 요청 (재고 10, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 12/15/15 | 36 | 126 |
| 2 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 9/10 | 11/14/14 | 24 | 130 |
| 3 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 7/10 | 10/12/12 | 23 | 136 |

### 동시 요청: REDIS_PUBSUB, 구독자 1, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 10/11/13 | 21 | 254 |
| 2 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 9/10/10 | 17 | 224 |
| 3 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 7/8/8 | 19 | 251 |

### 동시 요청: REDIS_PUBSUB, 구독자 1, 동일 회원 중복 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 6/8/9 | 26 | 128 |
| 2 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 4/9/12 | 40 | 115 |
| 3 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 4/7/8 | 28 | 128 |

### 동시 요청: REDIS_PUBSUB, 구독자 2, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 6/7/9 | 16 | 329 |
| 2 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 6/9/11 | 28 | 338 |
| 3 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 4/4/5 | 13 | 342 |

---

## 3회차 실행: 동시 요청
### 동시 요청: REDIS_PUBSUB, 구독자 1, 재고보다 많은 요청 (재고 10, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 9/10 | 17/19/19 | 40 | 139 |
| 2 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 9/10 | 9/11/11 | 19 | 137 |
| 3 | {PUBLISHED=10, SOLD_OUT=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 9/10 | 6/8/8 | 16 | 123 |

### 동시 요청: REDIS_PUBSUB, 구독자 1, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 8/9/9 | 21 | 284 |
| 2 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 9/13/13 | 28 | 223 |
| 3 | {PUBLISHED=50} | {1=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 14/15/16 | 45 | 372 |

### 동시 요청: REDIS_PUBSUB, 구독자 1, 동일 회원 중복 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 4/5/6 | 14 | 141 |
| 2 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 5/7/8 | 15 | 128 |
| 3 | {PUBLISHED=10, DUPLICATE=40} | {1=10} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 2/4/4 | 13 | 125 |

### 동시 요청: REDIS_PUBSUB, 구독자 2, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 구독자 작업 스레드 4)

| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |
|---:|---|---|---|---:|---:|---|---:|---|---:|---:|
| 1 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 7/8/8 | 19 | 332 |
| 2 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/7/7 | 16 | 376 |
| 3 | {PUBLISHED=50} | {2=50} | {DataIntegrityViolationException=50, SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 9/9/9 | 17 | 286 |
