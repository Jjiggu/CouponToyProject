# 선착순 쿠폰 지급 시스템

고동시성 환경에서 선착순 쿠폰을 정확하게 지급하는 토이 프로젝트.

**핵심 불변식**
- 지급 수량을 절대 초과하지 않는다
- 동일 사용자에게 1장만 지급한다

## 학습 단계

동시성 제어와 비동기 처리를 한 단계씩 구현하고 직접 실행해 확인한다. 계획, 실험 API(`/lab`, `local` 프로필 전용), 단계별 결과는 [docs/learning](docs/learning/README.md) 에 있다.

- [1단계: 명시적 락 없이 격리 수준 비교](docs/learning/01-isolation-level.md)
- [2단계: 비관적 락과 낙관적 락](docs/learning/02-optimistic-pessimistic.md)
- [3단계: Redis 지급 처리 — SET 명령만 / MULTI/EXEC / WATCH / Lua](docs/learning/03-redis-transaction-lua.md)
- [4단계: Spring @Async](docs/learning/04-async.md)
- [5단계: Redis Pub/Sub](docs/learning/05-pubsub.md)
- [6단계: Redis Streams](docs/learning/06-streams.md)
- [부하 테스트: 일정한 속도로 보낼 때 (Mac)](docs/learning/07-load-test.md)
- [AWS(EC2)에서 잰 쿠폰 1장 실제 지급 한계](docs/learning/08-load-test-ec2.md)
- [9단계: write-behind — Redis 를 기준으로, DB 는 나중에](docs/learning/09-write-behind.md)
- [10단계: 회원 확인에서 쓸데없는 트랜잭션 설정 문장 없애기](docs/learning/10-no-tx-member-check.md)
- [선착순 쿠폰 이벤트 오픈 시나리오 (10단계 방식)](docs/learning/11-event-load-test.md)

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

`DELETE /lab/data` 는 연결된 DB 의 쿠폰·지급·회원 테이블을 TRUNCATE 하고 Redis `coupon:*` 키를 지운다. 기존 데이터를 남기려면 별도 스키마로 띄운다.

```bash
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
SPRING_DATA_REDIS_DATABASE=2 ./gradlew bootRun
```

## API (`local` 프로필 전용, 인증 없음)

| Method | Path | Body | 설명 |
|---|---|---|---|
| POST | `/lab/members` | `{"count": 1000}` | 실험용 회원 일괄 생성 |
| POST | `/lab/coupons` | `{"name": "s1", "totalCount": 100}` | 실험용 쿠폰 생성 |
| POST | `/lab/coupons/{couponId}/issue` | `{"memberId": 1, "method": "SERIALIZABLE"}` | 방식 선택 지급: `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE`, `PESSIMISTIC`, `OPTIMISTIC`, `OPTIMISTIC_RETRY`, `REDIS_SET_ONLY`, `REDIS_MULTI_EXEC`, `REDIS_WATCH`, `REDIS_LUA`, `REDIS_ASYNC`·`REDIS_PUBSUB`·`REDIS_STREAM`·`REDIS_STREAM_ATOMIC`·`REDIS_WRITE_BEHIND`·`REDIS_WRITE_BEHIND_NO_TX`(202 지급 성공 응답). 격리 수준 방식은 `duplicateCheck: false` 가능 |
| GET | `/lab/coupons/{couponId}/result` | | 지급 결과, 수량, 검증 기준, 순서, 지연 |
| POST | `/lab/write-behind/coupons/{couponId}/reconcile` | | 9단계 대사: Redis 에만 있는 회원 INSERT, `issued_count` 를 행 수로 맞춤, DB 에만 있는 회원·재고 초과 보고 |
| DELETE | `/lab/data` | | 실험 데이터 초기화 |

모든 응답은 아래 형태로 반환된다. 품절·중복은 409, 데드락·락 대기 초과·낙관적 락 버전 충돌·비동기 큐 포화는 503, 없는 회원·쿠폰·경로는 404, 잘못된 입력은 400.

```json
{ "success": true,  "response": { }, "error": null }
{ "success": false, "response": null, "error": { "message": "...", "status": 409 } }
```

## Redis 지급 플로우 (3단계)

```
RedisIssueCouponService.issue(memberId, couponId, method)   ← DB 트랜잭션 없음

  [0] GET coupon:total:{couponId}                총수량 (없으면 DB 에서 읽어 다시 넣음)
  [1] Redis 지급 처리 (CouponIssuedRedisRepository)  "이미 지급 처리됨 → 재고 소진 → 지급 처리" 순서, 원자성만 방식별로 다름
        REDIS_SET_ONLY    SISMEMBER, SCARD, SADD 를 따로 보냄 (명령 사이에 다른 요청이 끼어든다)
        REDIS_MULTI_EXEC  MULTI SCARD SADD EXEC → Java 판단 → 재고 초과면 SREM
        REDIS_WATCH       WATCH → 읽기 → MULTI SADD EXEC (키가 바뀌었으면 다시, 최대 10회)
        REDIS_LUA         EVALSHA 한 번 (4~6단계 공통 기준)
  [2] IssueConfirmService.confirm() — 실제 쿠폰 지급, 별도 DB 트랜잭션
        INSERT coupon_issues                     UNIQUE(coupon_id, member_id)
        UPDATE coupons SET issued_count = issued_count + 1
         WHERE coupon_id = ? AND issued_count < total_count   0건이면 SOLD_OUT_AT_DB
  [3] [2] 실패 시 SREM 으로 Redis 지급 처리를 취소한다. UNIQUE 위반(이미 지급된 회원)은 취소하지 않는다
```

## 비동기 지급 플로우 (4단계, REDIS_ASYNC)

```
AsyncIssueCouponService.issue(memberId, couponId)        ← 요청 스레드
  [1] RedisIssueCouponService.reserve(LUA)                3단계와 같은 Redis 지급 처리
  [2] RedisIssueCouponService.confirmLater()  @Async     couponIssueExecutor 큐에 넣고 바로 반환 → HTTP 202 (지급 성공 응답)
        큐가 가득 차면 TaskRejectedException → SREM, HTTP 503
작업 스레드 (스레드 풀 couponIssueExecutor: 기본 4개, 큐 50000, JVM 메모리)
  [3] IssueConfirmService.confirm(), 실패 시 SREM          3단계와 같은 실제 지급·보상
결과 API: asyncResults(지급 성공 응답을 보낸 요청의 실제 지급 결과), pending(지급 성공 응답을 보냈지만 실제 지급 결과가 아직 없는 요청)
```

## Pub/Sub 지급 플로우 (5단계, REDIS_PUBSUB)

```
발행자 프로세스 (local)        PubSubIssueCouponService.issue()      ← 요청 스레드
  [1] RedisIssueCouponService.reserve(LUA)                          3단계와 같은 Redis 지급 처리
  [2] PUBLISH coupon:issue:requests "memberId:couponId" → HTTP 202 (PUBLISHED)
        돌려받는 "받은 구독자 수" 는 완료가 아니다. 구독자가 없으면 0 이고 메시지는 사라진다
구독자 프로세스 (pubsub-consumer)   PubSubIssueConsumer → 작업 스레드(couponIssueExecutor)
  [3] IssueConfirmService.confirm(), 실패 시 SREM                  3단계와 같은 실제 지급·보상
결과 API: publishReceivers(받은 구독자 수별 요청 수), redisOnlyMembers(Redis 지급 처리만 있고 실제 지급되지 않은 회원)
```

## Stream 지급 플로우 (6단계, REDIS_STREAM)

```
발행자 프로세스 (local)            StreamIssueCouponService               ← 요청 스레드
  REDIS_STREAM         Lua 지급 처리 → XADD coupon:issue:stream (두 명령 사이에서 죽으면 Redis 지급 처리만 남음)
  REDIS_STREAM_ATOMIC  EVALSHA 한 번: 지급 가능 여부 판단 → XADD → SADD
  → HTTP 202 (QUEUED)
소비자 프로세스 (stream-consumer)    그룹 coupon-issuers, 소비자 4개(consumer-{pid}-n)
  XREADGROUP → IssueConfirmService.confirm() → 커밋 뒤 XACK
    UNIQUE 위반(이미 지급, 재처리 포함) → XACK / 품절·없는 회원 → SREM, XACK / 그 밖의 실패 → ACK 안 함(pending 에 남음)
  1초마다 XAUTOCLAIM(최소 유휴 5초)으로 회수해 다시 처리. 전달 3회를 넘으면 coupon:issue:stream:dead 에 남기고 SREM, XACK
결과 API: redisOnlyMembers(Redis 지급 처리만 있고 실제 지급 안 된 회원), stream(스트림 전체의 길이·대기·pending·따로 보관 수)
```

## write-behind 플로우 (9단계, REDIS_WRITE_BEHIND)

```
요청 (local)          회원 존재 확인 → EVALSHA: 중복 → 재고 → XADD coupon:wb:stream → SADD  → 202 (QUEUED). DB 에 쓰지 않는다
적재기 (wb-loader)    XREADGROUP (자기 pending 먼저) COUNT 100 → 한 트랜잭션에 INSERT ... ON DUPLICATE KEY (쿠폰 행 갱신 없음) → 커밋 뒤 XACK
                      묶음 실패 시 한 건씩 다시. 전달 3회 초과는 coupon:wb:stream:dead 에 남기고 XACK (Redis 지급 처리는 그대로, 대사가 채움)
대사 (API)            DB 먼저 읽고 Redis 명단과 비교 → Redis 에만 있는 회원 INSERT, issued_count = COUNT(*)
Redis 가 기준이라 DB 의 조건부 UPDATE 방어가 없다. Redis 가 기록을 잃으면(영속화 끔 + 재시작) 초과 지급을 막지 못한다
```

10단계(`REDIS_WRITE_BEHIND_NO_TX`)는 9단계와 같고, 회원 확인만 `MemberExistsRepository` 로 트랜잭션 없이 SELECT 한 문장을 보낸다. 적재기·대사·스트림은 9단계 것을 그대로 쓴다.

## Redis 키

| 키 | 자료형 | 설명 |
|---|---|---|
| `coupon:total:{couponId}` | String | 총 지급 수량 (쿠폰 생성 시 웜업, 불변) |
| `coupon:issued:{couponId}` | SET | Redis 지급 처리된 회원 집합 (실제 쿠폰 지급 전 회원도 들어 있다) |
| `coupon:issue:stream` | Stream | 6단계 실제 지급 요청. 그룹 `coupon-issuers`. ACK 해도 지워지지 않고 자르지 않는다 |
| `coupon:issue:stream:dead` | Stream | 6단계 전달 횟수 상한을 넘긴 메시지 |
| `coupon:wb:stream` | Stream | 9단계 적재 요청. 그룹 `coupon-wb-loaders`, 소비자 이름 `loader` 하나 |
| `coupon:wb:stream:dead` | Stream | 9단계 전달 횟수 상한을 넘겨 적재를 포기한 메시지 |
