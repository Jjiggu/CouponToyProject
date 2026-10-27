# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

선착순 쿠폰 지급 처리 토이 프로젝트. Spring Boot + MySQL + Redis(SET) 기반으로 고동시성 쿠폰 지급을 구현한다.

## Commands

### Build & Run
```bash
./gradlew build               # 전체 빌드
./gradlew bootRun             # 애플리케이션 실행
```

### Tests
```bash
./gradlew test                                          # 전체 테스트 실행
./gradlew test --tests "*.IsolationIssueCouponServiceTest"                      # 특정 테스트 클래스 실행
./gradlew test --tests "*.IsolationIssueCouponServiceTest.concurrentRequests"     # 단일 테스트 실행
LAB_CHANGE_MYSQL_GLOBALS=true ./gradlew test --tests "*.IsolationLockSettingsExperimentTest"  # MySQL 전역 락 설정을 바꾸는 실험 (끝나면 복구)
```

> 통합 테스트(`@SpringBootTest`)는 MySQL과 Redis가 로컬에서 실행 중이어야 한다. 테스트는 `CouponToyProject_test` DB와 Redis db 1을 쓴다.
> 단계별 실행 기록은 `build/lab/stage1-isolation.md`, `build/lab/stage1-lock-settings.md`, `build/lab/stage2-lock.md`, `build/lab/stage3-redis.md`, `build/lab/stage4-async.md`, `build/lab/stage5-pubsub.md`, `build/lab/stage6-stream.md`, `build/lab/stage9-write-behind.md`, `build/lab/stage10-no-tx.md` 에 남는다. 학습 단계 문서는 `docs/learning/`.

### Infrastructure (Docker)
```bash
docker compose up -d   # 루트 docker-compose.yml: mysql, redis 만 (각 1 CPU / 1GB, Redis 영속화 끔)
```
- MySQL: `localhost:3306`
- Redis: `localhost:6379`

## Architecture

### Domain Structure
패키지는 도메인별로 구성되며, 각 도메인은 `controller / service / repository / model / dto` 레이어를 가진다.

- **Member** – 실험용 회원 엔티티 (인증 없음. email/password/role 컬럼은 기존 DB 스키마 호환용)
- **Coupon** – 쿠폰 엔티티와 생성
- **CouponIssue** – 쿠폰 지급. 학습 단계별 비교 구현 (`IsolationIssueCouponService`, `PessimisticIssueCouponService`, `OptimisticIssueCouponService`, `RedisIssueCouponService` + `IssueConfirmService`, `AsyncIssueCouponService`, `PubSubIssueCouponService` + `PubSubIssueConsumer`, `StreamIssueCouponService` + `StreamIssueConsumer`). 낙관적 락은 같은 `coupons` 테이블에 매핑한 `VersionedCoupon`(@Version)만 쓴다. `Coupon` 에는 @Version 을 넣지 않는다
- **WriteBehind** – 9단계 write-behind. `WriteBehindIssueService`(요청: Lua 로 지급 가능 여부 판단·Redis 지급 처리·적재 요청 기록), `WriteBehindLoader`(적재: INSERT 만, 대사), `wb-loader` 프로필 적재기 프로세스. 기존 코드에는 방식 이름·`LabService` 분기·202 목록만 등록
- **WriteBehindNoTx** – 10단계. 9단계와 같고 회원 확인만 `MemberExistsRepository`(`@Transactional(readOnly = true, propagation = SUPPORTS)`, 트랜잭션 없이 SELECT 한 문장)로. 적재기·대사·스트림은 9단계 것을 그대로 쓴다
- **Lab** – `local` 프로필 전용 실험 API(`/lab/**`, 인증 없음). 데이터 준비, 방식 선택 지급, 결과 조회, 초기화. 단일 진입점 `LabService.issue()` 가 도착 순서와 결과를 기록

### API Endpoints

| Method | Path | 설명 |
|---|---|---|
| POST | `/lab/members`, `/lab/coupons` | 실험 데이터 준비 (local 전용) |
| POST | `/lab/coupons/{couponId}/issue` | `{memberId, method, duplicateCheck?}` 로 방식 선택 지급 (local 전용). `REDIS_ASYNC`, `REDIS_PUBSUB`, `REDIS_STREAM`, `REDIS_STREAM_ATOMIC`, `REDIS_WRITE_BEHIND`, `REDIS_WRITE_BEHIND_NO_TX` 는 202(지급 성공 응답) |
| GET | `/lab/coupons/{couponId}/result` | 검증 기준·순서·지연·락 카운터 (local 전용) |
| POST | `/lab/write-behind/coupons/{couponId}/reconcile` | 9단계 대사: Redis 에만 있는 회원 INSERT, `issued_count` 를 행 수로 맞춤, DB 에만 있는 회원·재고 초과 보고 (local 전용) |
| DELETE | `/lab/data` | 설정된 DB 3개 테이블 TRUNCATE + Redis `coupon:*` 삭제 (local 전용) |

### Redis 지급 플로우 (3단계)

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

### 비동기 지급 플로우 (4단계, REDIS_ASYNC)

```
AsyncIssueCouponService.issue(memberId, couponId)        ← 요청 스레드
  [1] RedisIssueCouponService.reserve(LUA)                3단계와 같은 Redis 지급 처리
  [2] RedisIssueCouponService.confirmLater()  @Async     couponIssueExecutor 큐에 넣고 바로 반환 → HTTP 202 (지급 성공 응답)
        큐가 가득 차면 TaskRejectedException → SREM, HTTP 503
작업 스레드 (스레드 풀 couponIssueExecutor: 기본 4개, 큐 50000, JVM 메모리)
  [3] IssueConfirmService.confirm(), 실패 시 SREM          3단계와 같은 실제 지급·보상
결과 API: asyncResults(지급 성공 응답을 보낸 요청의 실제 지급 결과), pending(지급 성공 응답을 보냈지만 실제 지급 결과가 아직 없는 요청)
```

### Pub/Sub 지급 플로우 (5단계, REDIS_PUBSUB)

```
발행자 프로세스 (local)        PubSubIssueCouponService.issue()      ← 요청 스레드
  [1] RedisIssueCouponService.reserve(LUA)                          3단계와 같은 Redis 지급 처리
  [2] PUBLISH coupon:issue:requests "memberId:couponId" → HTTP 202 (PUBLISHED)
        돌려받는 "받은 구독자 수" 는 완료가 아니다. 구독자가 없으면 0 이고 메시지는 사라진다
구독자 프로세스 (pubsub-consumer)   PubSubIssueConsumer → 작업 스레드(couponIssueExecutor)
  [3] IssueConfirmService.confirm(), 실패 시 SREM                  3단계와 같은 실제 지급·보상
결과 API: publishReceivers(받은 구독자 수별 요청 수), redisOnlyMembers(Redis 지급 처리만 있고 실제 지급되지 않은 회원)
```

### Stream 지급 플로우 (6단계, REDIS_STREAM)

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

### write-behind 플로우 (9단계, REDIS_WRITE_BEHIND)

```
요청 (local)          회원 존재 확인 → EVALSHA: 중복 → 재고 → XADD coupon:wb:stream → SADD  → 202 (QUEUED). DB 에 쓰지 않는다
적재기 (wb-loader)    XREADGROUP (자기 pending 먼저) COUNT 100 → 한 트랜잭션에 INSERT ... ON DUPLICATE KEY (쿠폰 행 갱신 없음) → 커밋 뒤 XACK
                      묶음 실패 시 한 건씩 다시. 전달 3회 초과는 coupon:wb:stream:dead 에 남기고 XACK (Redis 지급 처리는 그대로, 대사가 채움)
대사 (API)            DB 먼저 읽고 Redis 명단과 비교 → Redis 에만 있는 회원 INSERT, issued_count = COUNT(*)
Redis 가 기준이라 DB 의 조건부 UPDATE 방어가 없다. Redis 가 기록을 잃으면(영속화 끔 + 재시작) 초과 지급을 막지 못한다
```

### Redis Key 구조
| 키 패턴 | 자료형 | 용도 |
|---|---|---|
| `coupon:total:{couponId}` | String | 쿠폰 총 지급 수량 캐시 (쿠폰 생성 시 웜업, 불변) |
| `coupon:issued:{couponId}` | SET | Redis 지급 처리된 회원 집합 (실제 쿠폰 지급 전 회원 포함, 중복 방지 + 수량 추적) |
| `coupon:issue:stream` | Stream | 6단계 실제 지급 요청. 그룹 `coupon-issuers`. ACK 해도 지워지지 않고 자르지 않는다 |
| `coupon:issue:stream:dead` | Stream | 6단계 전달 횟수 상한을 넘긴 메시지 |
| `coupon:wb:stream` | Stream | 9단계 적재 요청. 그룹 `coupon-wb-loaders`, 소비자 이름 `loader` 하나 |
| `coupon:wb:stream:dead` | Stream | 9단계 전달 횟수 상한을 넘겨 적재를 포기한 메시지 (원래 필드, originalId, deliveries, error) |

### Concurrency Strategy
- **Redis 지급 처리**: 명령 하나는 원자적이지만 명령 사이에는 다른 요청이 끼어든다. 판단과 지급 처리를 한 번에 하려면 Lua(또는 WATCH)를 쓴다. MULTI/EXEC 는 묶어 실행할 뿐 결과로 분기하지 못해 SREM 으로 되돌린다
- **DB 조건부 UPDATE + UNIQUE**: Redis 통과 후 DB 수준에서 최종 수량·중복 검증. Redis 와 MySQL 은 한 트랜잭션이 아니다
- **보상 SREM**: 실제 쿠폰 지급 실패 시 Redis 지급 처리를 취소한다. Redis 지급 처리 뒤 프로세스가 죽으면 Redis 지급 처리만 남는다(4~6단계 주제)
- **비동기 실제 지급 (4단계)**: 지급 성공 응답(202)은 실제 지급 완료가 아니다. 실제 지급 작업은 JVM 메모리 큐에 있으므로 프로세스가 죽으면 Redis 지급 처리만 남는다. `@Async` 는 다른 빈에서 호출해야 프록시를 지나고, 작업 스레드(스레드 풀 안에서 실제 지급을 실행하는 스레드)에는 요청 스레드의 트랜잭션이 없다
- **Pub/Sub (5단계)**: 메시지를 저장하지 않는다. 구독자가 없거나 끊긴 동안 보낸 메시지, 받았지만 처리 전에 구독자가 죽은 메시지는 사라진다. 채널은 Redis DB 번호와 관계없이 서버 전체에서 하나다. 구독자 프로세스는 `SPRING_PROFILES_ACTIVE=pubsub-consumer` 로 띄운다
- **Streams (6단계)**: DB 커밋 뒤에만 XACK. 재처리는 UNIQUE 위반으로 끝나 ACK 한다(실제 지급 트랜잭션이 INSERT 먼저라 issued_count 는 다시 늘지 않음). 일시적 실패는 ACK·보상 모두 하지 않고 회수(XAUTOCLAIM)로 다시 처리, 전달 3회 초과면 따로 보관 후 SREM. 소비자 프로세스는 `SPRING_PROFILES_ACTIVE=stream-consumer`
- **실패 지점**: `global/failure/FailurePoints` — 테스트가 회원 단위로 멈춤·실패·종료 흉내를 건다. `LAB_HALT_AT=<지점>` 이면 그 지점에서 JVM 을 즉시 종료한다

### Global Infrastructure (`global` 패키지)
- **인증 없음**: 모든 API 는 `Lab` 패키지에 있고 `@Profile("local")` 로만 등록된다
- **API 응답**: `ApiUtils.success()` / `ApiUtils.error()` → `ApiResult<T>` 래퍼
- **예외 처리**: `GlobalControllerAdvice`에서 도메인 예외를 HTTP 응답으로 변환
  - 400: 입력 검증·파싱 오류
  - 404: `CouponNotFoundException`, `MemberNotFoundException`, 없는 경로
  - 409: `CouponSoldOutException`, `IsAlreadyIssued`, `DataIntegrityViolationException`(UNIQUE 위반)
  - 503: `PessimisticLockingFailureException`(데드락, 락 대기 시간 초과), `OptimisticLockingFailureException`(버전 충돌), `TaskRejectedException`(비동기 큐 포화)

### Tech Stack
- Java 17, Spring Boot 3.4.1, Spring Data JPA, Spring Data Redis, Lombok
- MySQL 8.0.36, Redis 7.2
- Test: JUnit 5, k6 (`performance/k6/coupon-issue.js`)

## 문서 용어

이 파일과 `docs/learning/`, `performance/` 의 README 는 여기어때 기술 블로그 "Redis&Kafka를 활용한 선착순 쿠폰 이벤트 개발기 (feat. 네고왕)" 의 표현을 따른다.

| 뜻 | 쓰는 말 | 쓰지 않는 말 |
|---|---|---|
| 쿠폰을 주는 일 전반 | 지급 (초과 지급, 중복 지급, 총 지급 수량, 지급 TPS) | 발급 |
| Redis SET 에 회원을 넣는 것 (SADD) | Redis 지급 처리 | 예약, 선점 |
| Redis 에서 수량·중복을 따지는 것 | 지급 가능 여부 판단 | 예약 판단 |
| DB 에 저장하는 것 (`coupon_issues` INSERT 등) | 실제 쿠폰 지급 (실제 지급 상한, 실제 지급 TPS) | 확정 |
| HTTP 202 응답 | 지급 성공 응답 | 접수 |

- 코드·클래스·메서드·enum·API 필드·Redis 키·설정 이름(`IssueConfirmService`, `ACCEPTED`, `coupon:issued:{couponId}` 등), 실제 에러 메시지 문구, 외부 글 인용은 그대로 둔다
- `docs/learning/results/` 의 실행 원본과 k6·리포트 스크립트(`performance/k6/*.js`, `performance/ec2/*.py`)가 찍는 출력 문구(`접수 처리량`, `발급 완료 처리량`, `발급 TPS` 등)는 그대로 둔다
- 9단계 이후 write-behind 의 "적재" 는 그대로 쓴다

## 행동 지침

> 속도보다 신중함에 무게를 둔다. 사소한 작업에는 판단력을 활용할 것.

### 1. 코딩 전에 생각하라
추측하지 말 것. 혼란을 숨기지 말 것. 트레이드오프를 드러낼 것.

구현 전에:
- 가정을 명시적으로 진술할 것. 불확실하면 물어볼 것.
- 여러 해석이 가능하면 제시할 것 — 조용히 하나를 선택하지 말 것.
- 더 단순한 접근법이 있으면 말할 것. 필요하면 반론을 제기할 것.
- 무언가 불명확하면 멈출 것. 무엇이 혼란스러운지 명명할 것. 질문할 것.

### 2. 단순함 우선
문제를 해결하는 최소한의 코드. 추측성 코드는 없어야 한다.

- 요청받은 것 이상의 기능을 추가하지 말 것.
- 한 번만 쓰이는 코드에 추상화를 만들지 말 것.
- 요청되지 않은 "유연성"이나 "설정 가능성"을 넣지 말 것.
- 발생 불가능한 시나리오에 대한 에러 처리를 하지 말 것.
- 200줄을 작성했는데 50줄로 가능하다면, 다시 작성할 것.
- 스스로에게 물어볼 것: "시니어 엔지니어가 이걸 보고 과하게 복잡하다고 할까?" 그렇다면 단순화할 것.

### 3. 외과적 수정
반드시 수정해야 하는 것만 건드릴 것. 자신이 만든 지저분함만 정리할 것.

기존 코드를 수정할 때:
- 인접한 코드, 주석, 포맷팅을 "개선"하지 말 것.
- 고장나지 않은 것을 리팩터링하지 말 것.
- 본인이라면 다르게 하더라도 기존 스타일에 맞출 것.
- 관련 없는 데드 코드를 발견하면 언급은 하되 — 삭제하지 말 것.

본인의 변경으로 인해 고아 코드가 생겼을 때:
- 본인의 변경으로 인해 사용되지 않게 된 import/변수/함수는 제거할 것.
- 요청받지 않는 한 기존에 있던 데드 코드는 제거하지 말 것.

검증 기준: 변경된 모든 줄은 사용자의 요청에 직접적으로 연결되어야 한다.

### 4. 목표 중심 실행
성공 기준을 정의할 것. 검증될 때까지 반복할 것.

작업을 검증 가능한 목표로 변환할 것:
- "유효성 검사 추가" → "잘못된 입력에 대한 테스트를 작성하고, 통과시킬 것"
- "버그 수정" → "재현하는 테스트를 작성하고, 통과시킬 것"
- "X 리팩터링" → "리팩터링 전후로 테스트가 통과하는지 확인할 것"

다단계 작업의 경우 간략한 계획을 명시할 것:
1. [단계] → 검증: [확인사항]
2. [단계] → 검증: [확인사항]
3. [단계] → 검증: [확인사항]
