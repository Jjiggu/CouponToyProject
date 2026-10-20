# 선착순 쿠폰 발급: 동시성 제어와 비동기 처리 학습

한 단계씩 구현하고, 직접 실행해서 확인한 것만 적는다. 이전 문서(`docs/performance`, `docs/coupon-performance-test`, `docs/overview.md` 등)는 JWT 인증과 이전 엔드포인트 기준이며 결과 보존용이다. 그 수치를 새 구현의 성과로 쓰지 않는다.

| 단계 | 주제 | 상태 |
|---|---|---|
| 1 | [명시적 락 없이 격리 수준 비교](01-isolation-level.md) | 구현·실행 완료 (변형 실험 포함) |
| 2 | [낙관적 락과 비관적 락](02-optimistic-pessimistic.md) | 구현·실행 완료 |
| 3 | [Redis 예약: SET 명령만 / MULTI/EXEC / WATCH / Lua](03-redis-transaction-lua.md) | 구현·실행 완료 |
| 4 | [Spring @Async](04-async.md) | 구현·실행 완료 |
| 5 | [Redis Pub/Sub](05-pubsub.md) | 구현·실행 완료 |
| 6 | [Redis Streams](06-streams.md) | 구현·실행 완료 |
| - | [부하 테스트: 일정한 속도로 보낼 때](07-load-test.md) | 실행 완료 |

## 공통 검증 기준

결과 API(`GET /lab/coupons/{id}/result`)가 매번 계산한다.

| 기준 | 결과 필드 |
|---|---|
| 쿠폰별 사용자당 최대 1장 | `checks.atMostOncePerMember` (`coupon_issues` 행 수 = 회원 수) |
| 실제 발급 건수 ≤ 전체 수량 | `checks.notOverIssued` |
| DB 발급 행 = `issued_count` | `checks.issuedCountMatchesRows` |
| 유효 요청이 충분하면 수량만큼 발급 | `checks.fullyIssued` (유효 요청 < 수량이면 `null`) |
| 접수·완료·실패·미완료 구분 | `requests` (진입점에서 본 결과), `issueRows` (DB에서 본 완료). 비동기 단계부터 둘이 달라진다 |

결과 분류(`IssueOutcome`): `SUCCESS`(커밋 완료), `ACCEPTED`(접수만), 거절 `SOLD_OUT`·`SOLD_OUT_AT_DB`·`DUPLICATE`·`UNIQUE_VIOLATION`(HTTP 409), 트랜잭션 실패 `DEADLOCK`·`LOCK_WAIT_TIMEOUT`·`OPTIMISTIC_CONFLICT`·비동기 큐 포화 `REJECTED`(HTTP 503), Pub/Sub 발행 `PUBLISHED`·Stream 등록 `QUEUED`(HTTP 202, 완료 아님), `NOT_FOUND`, `ERROR`.

### 순서는 세 가지를 따로 기록한다

- **도착 순서**: 단일 진입점 `LabService.issue()` 에 들어온 순서(쿠폰별 `AtomicLong`). 모든 단계의 공통 기준이다.
- **당첨자 선정 순서**: 누가 수량을 차지했는지 결정되는 지점. 1~2단계는 DB 커밋, 3단계부터는 Redis 예약 시점이다. DB 락을 기다리는 요청은 MySQL 8.0.20+ 의 CATS 가 정한 순서(가중치가 큰 쪽 먼저, 같으면 오래 기다린 쪽 먼저)로 락을 받는다. 도착 순서와 다를 수 있다([2단계 4-6](02-optimistic-pessimistic.md#4-6-락-부여-순서-cats-보완-실험)).
- **DB 처리 완료 순서**: 커밋된 순서. 동기 방식은 진입점에서 커밋 후 반환 순서로 기록한다.

결과 API 의 `ordering`
- `firstNValidWon`: 먼저 도착한 유효 요청 N명(N = min(수량, 유효 요청)) 중 실제 당첨자 수. N/N 이어야 선착순이다.
- `winnersOutsideFirstN`: 먼저 온 N명에 들지 못했는데 당첨된 수
- `completedOutOfArrivalOrder`: 성공 요청 중 도착 순위와 완료 순위가 다른 수

유효 요청 = 회원별 첫 요청(없는 회원 제외). 테스트 요청 전체를 직렬화해서 결과를 좋게 만들지 않는다.

## 실험 환경

API (`local` 프로필에서만 등록, 인증 없음)

| Method | Path | Body | 설명 |
|---|---|---|---|
| POST | `/lab/members` | `{"count": 1000}` | 회원 일괄 생성, `firstMemberId`~`lastMemberId` 반환 |
| POST | `/lab/coupons` | `{"name": "s1", "totalCount": 100}` | 쿠폰 생성. 쿠폰마다 요청 기록과 InnoDB 락 카운터 기준값을 새로 잡는다 |
| POST | `/lab/coupons/{id}/issue` | `{"memberId": 1, "method": "SERIALIZABLE"}` | 방식 선택 발급. `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE`, `PESSIMISTIC`, `OPTIMISTIC`, `OPTIMISTIC_RETRY`, `REDIS_SET_ONLY`, `REDIS_MULTI_EXEC`, `REDIS_WATCH`, `REDIS_LUA`, `REDIS_EVENT`. 격리 수준 방식은 `"duplicateCheck": false` 로 중복 확인 SELECT 를 뺄 수 있다 |
| GET | `/lab/coupons/{id}/result` | | 검증 기준, 요청 결과, 순서, 서버 측 지연, 락 카운터 변화량 |
| DELETE | `/lab/data` | | **설정된 DB 의 `coupon_issues`·`coupons`·`members` 를 TRUNCATE 하고 Redis `coupon:*` 키를 지운다** |

데이터 분리
- 실험마다 새 쿠폰을 만든다. Redis 키와 결과 집계가 쿠폰 ID 로 나뉜다.
- 테스트: DB `CouponToyProject_test`(create-drop), Redis db 1.
- 직접 실행: 기본 설정은 기존 DB `CouponToyProject`(이전 성능 실험 데이터가 들어 있음)와 Redis db 0 을 쓴다. 지우고 싶지 않다면 [1단계 문서](01-isolation-level.md#3-직접-실행할-명령과-바꿔볼-조건)처럼 `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_DATABASE` 로 따로 띄운다.

부하 도구: `performance/k6/coupon-issue.js`. setup 에서 회원·쿠폰을 만들고, 발급 요청 응답 시간만 `coupon_issue_http_duration` 에 모으고, teardown 에서 결과 API 와 접수/발급 완료 처리량을 출력한다.

## 2~6단계 계획

모든 단계의 산출물: 질문과 예상 → 구현과 명령 실행 순서 → 실행 명령과 바꿔볼 조건 → 관찰 결과와 원인 → 보장 범위와 한계 → 다음 단계로 가는 이유. 정합성 테스트를 먼저 하고 성능은 그다음에 잰다. 장애 실험과 정상 성능 결과는 따로 적는다.

### 2단계: 낙관적 락과 비관적 락 (완료)
- [02-optimistic-pessimistic.md](02-optimistic-pessimistic.md). 기존 `@Retry`/`OptimisticLockRetryAspect` 는 쓰지 않고 삭제했다(이유는 2단계 문서 2절).

### 3단계: Redis Transaction 과 Lua (완료)
- [03-redis-transaction-lua.md](03-redis-transaction-lua.md). 4~6단계 공통 기준: Lua 예약(`reserveWithLua`) + `IssueConfirmService`.

### 4~6단계 공통 조건
3단계에서 검증한 예약 로직과 같은 DB 확정 로직을 그대로 쓰고, 작업을 전달·실행·복구하는 방식만 바꾼다. 공통 로직을 바꾸면 이유와 비교 조건 변화를 기록한다. 모든 조합을 만들지 않는다.

### 4단계: Spring @Async
- [04-async.md](04-async.md). 기존 이벤트 방식(`@TransactionalEventListener` + `@Async`)을 확인한 뒤 `@Async` 메서드 하나로 다시 썼다. 3단계 Lua 예약과 `IssueConfirmService` 는 그대로, DB 확정만 스레드 풀(`couponIssueExecutor`)로 넘긴다.
- 결과 API 의 `asyncResults`(접수된 요청의 확정 결과), `pending`(확정 결과가 아직 없는 요청), `confirmLatencyMs`(진입 → 커밋)로 접수와 완료를 나눠 센다.

### 5단계: Redis Pub/Sub
- [05-pubsub.md](05-pubsub.md). 발행자(`local`)와 구독자(`pubsub-consumer`) 프로세스를 같은 코드의 실행 설정으로 나눴다. 3단계 예약·확정을 그대로 쓰고, 구독자의 확정은 4단계와 같은 스레드 풀에서 한다.
- 같은 코드베이스를 실행 설정으로 발행자/소비자 프로세스로 나눈다.
- 정상 전달, 구독자 없음, 구독자 중단·재시작, 수신 후 DB 실패, 구독자 2개가 같은 메시지를 각각 받는 경우.
- PUBLISH 성공이나 구독자 수를 완료로 보지 않는다. 별도 영속 큐나 복구 장치를 만들지 않고, 감지·보상 가능한 실패와 근거 없이 사라지는 유실을 나눠 적는다.

### 6단계: Redis Streams
- [06-streams.md](06-streams.md). 발행자(`local`)와 소비자(`stream-consumer`) 프로세스. 같은 그룹에 소비자를 늘려 동시 처리하고, DB 커밋 뒤에 ACK. 보상(SREM)은 다시 해도 같은 거절과 전달 횟수 상한 초과 때만(이유는 문서 2절).
- Consumer Group, 대기 메시지 vs pending, DB 커밋 후 ACK, XAUTOCLAIM 회수, 재시도 상한과 반복 실패 보관.
- DB 커밋 후 ACK 전 종료, Redis 예약 후 XADD 전 종료를 재현한다. 예약과 메시지 등록을 함께 처리하는 최소 개선을 검토한다.
- 재처리 멱등성(UNIQUE 예외만 잡으면 `issued_count` 까지 맞는지)과 메시지 유실 방지를 구분한다.
- Redis 재시작 시 보존 범위는 실제 설정 기준으로 설명한다. 현재 `docker-compose.yml`(미커밋 변경)은 `--appendonly no --save ""` 인데, 볼륨에 남은 옛 `dump.rdb` 를 기동 시 읽어 키가 살아 있었다.

## 현재 코드 정리 내역

### 수정·추가
| 대상 | 내용 |
|---|---|
| `IsolationIssueCouponService` | 1단계 구현 (신규). RC·RR·SERIALIZABLE, 변형 실험용 `duplicateCheck` |
| `Lab/*` | 실험 API, 진입점 요청 기록(`IssueRequestLog`), 결과 분류(`IssueOutcome`) (신규) |
| `CouponIssueStrategy` | 방식 선택 값으로 재사용, `READ_COMMITTED`·`REPEATABLE_READ`·`SERIALIZABLE`·`OPTIMISTIC`·`OPTIMISTIC_RETRY` 추가 |
| `VersionedCoupon`, `VersionedCouponRepository` | 2단계 낙관적 락 전용 매핑 (신규). 같은 `coupons` 테이블, `version bigint default 0 not null` |
| `OptimisticIssueCouponService` | 2단계 구현 (신규). 재시도 없음 / 버전 충돌만 제한된 재시도 |
| 비관적·Redis·Event 서비스 | `MemberDetails` 대신 `memberId` 를 받는다 (실험 API 에서 호출하기 위해) |
| `GlobalControllerAdvice`, `ErrorCode` | 잘못된 입력 400, UNIQUE 위반 409, 락 실패 503, 없는 경로 404 (이전에는 모두 500). 인증·회원가입용 핸들러와 코드 제거 |
| `build.gradle` | thymeleaf(템플릿 없음), security, jwt 의존성 제거 |
| `Member`, `MemberRepository` | 비밀번호 인코딩과 이메일/이름 조회 제거. 컬럼은 유지 |
| `IssueCouponService`, `CouponService` | 요청 바디로 회원을 바꾸던 오버로드, 쿠폰 조회 메서드 제거 |
| `IssueCouponService` → `RedisIssueCouponService`, `IssueConfirmService` | 3단계에서 다시 작성. Redis 예약을 DB 트랜잭션 밖으로, DB 확정을 별도 트랜잭션으로, UNIQUE 위반 시 SREM 하지 않음 (3단계 문서 2절) |
| `CouponIssuedRedisRepository` | 예약 방식 4가지(SET 명령만, MULTI/EXEC, WATCH, Lua), 회원 목록 조회 |
| `FailurePoints` | 장애 실험용 실패 지점 (신규). 평소에는 아무 일도 하지 않음 |
| `RedisReservationTest` | 3단계 끼어들기, SREM 전 틈, 예약 후 실패·종료, 보상 경합, 동시 요청 비교 |
| `EventIssueCouponService` → `AsyncIssueCouponService`, `AsyncConfig` | 4단계에서 다시 작성. 이벤트·리스너 대신 `@Async` 메서드 하나(`RedisIssueCouponService.confirmLater`), 작업 스레드 수·큐 용량 설정 (4단계 문서 2절) |
| `RedisIssueCouponService` | 예약(`reserve`)과 확정·보상(`confirmOrCancel`)을 나눠 4단계가 같은 코드를 쓰게 함. 3단계 동작은 같다 |
| `IssueRequestLog`, `LabService.result` | 비동기 확정 결과·미완료·진입 → 커밋 시간. 요청 기록을 DB 보다 먼저 읽고 DB 는 읽기 트랜잭션 하나에서 읽는다 |
| `GlobalControllerAdvice`, `ErrorCode`, `IssueOutcome` | 비동기 큐 포화(`TaskRejectedException`) 503, `REJECTED` |
| `AsyncIssueTest`, `TransactionalEventListenerTest` | 4단계 접수와 확정 사이, 확정 실패, 큐 포화와 거부 정책, 동시 요청 / 트랜잭션 이벤트 리스너 동작 확인 |
| `performance/k6/coupon-issue.js` | `REDIS_ASYNC` 는 워밍업 확정이 끝난 뒤 측정하고, 확정이 모두 끝날 때까지 결과를 다시 조회. `REDIS_PUBSUB` 은 Redis 에만 있는 회원이 0 이 되거나 2초 동안 줄지 않을 때까지 |
| `PubSubIssueCouponService`, `PubSubIssueConsumer`, `PubSubConsumerConfig` | 5단계 발행자·구독자 (신규). 구독은 `pubsub-consumer` 프로필에서만 |
| `RedisIssueCouponService.confirmOrCancel` | 4·5단계가 같은 확정·보상을 쓰도록 공개 |
| `StreamIssueCouponService`, `StreamIssueConsumer`, `StreamConsumerConfig` | 6단계 발행자·소비자 (신규). 읽기는 `stream-consumer` 프로필에서만, 회수는 1초마다 XAUTOCLAIM |
| `CouponIssuedRedisRepository.reserveAndEnqueueWithLua`, `RedisIssueCouponService.reserveAndEnqueue` | 6단계 최소 개선: Lua 예약과 XADD 를 한 스크립트로 |
| `StreamIssueTest` | 6단계 대기와 pending, 커밋 뒤 ACK 전 종료와 재처리, 일시적 실패 재시도, 전달 횟수 상한, 소비자 2개, 예약 뒤 XADD 전 종료, XADD 실패, 스트림 자르기, 동시 요청 |
| `PubSubIssueTest` | 5단계 구독자 없음·재구독, 정상 전달, 받은 뒤 DB 실패, 받은 뒤 처리 전 종료, 구독자 2개, 구독자 2개 중 한쪽 실패, 동시 요청 |
| `docker-compose.yml` | app 의 `JWT_SECRET_KEY` 제거 (나머지 미커밋 변경 그대로) |
| 테스트 공통 지원 | 회원을 회원가입(BCrypt) 대신 리포지토리로 저장, `local` 프로필, Redis db 1. 순서 고정·동시 요청 장치는 `ConcurrencyExperimentSupport` (1·2단계 공용) |
| `OptimisticPessimisticLockTest` | 2단계 순서 고정, 버전 검사 우회, CATS 락 부여 순서, 동시 요청 비교 |
| `IsolationLockSettingsExperimentTest` | 데드락 감지·락 대기 시간 변형. MySQL 전역 설정을 바꾸므로 `LAB_CHANGE_MYSQL_GLOBALS=true` 일 때만 실행 |
| `LabControllerTest` | 삭제한 컨트롤러 통합 테스트가 보던 방식별 성공·중복·품절 응답 형식을 모든 방식에 대해 확인 |
| `performance/k6/coupon-issue.js`, `performance/README.md` | JWT 토큰 대신 실험 API 사용 |

### 유지
| 대상 | 이유 |
|---|---|
| `PessimisticIssueCouponService`, `findByCouponIdForUpdate` | 2단계 비관적 락 비교 대상 (변경 없음) |
| `monitoring/` | Grafana 대시보드 (앱 지표 패널 4개는 4단계에서 삭제) |
| `Member` 의 email/password/role 컬럼 | 기존 DB 볼륨의 NOT NULL 컬럼과 맞추기 위해 |
| `performance/sql/consistency-check.sql` | 결과 API 를 원시 SQL 로 교차 확인 |
| `performance/scripts/aggregate-k6-results.js` | 이전 결과 문서 생성기. 실행하면 `latest-summary.md` 를 덮어써서 이번에는 실행하지 않음 |
| `perf/` (미추적, 311MB) | 회원가입·로그인 HTTP 흐름 기반의 이전 도구와 결과. 건드리지 않음 |

### 삭제 (2026-10-15)
파일은 git 이력에 남아 있다.

| 대상 | 이유 |
|---|---|
| `SecurityConfig`, `global/security/*`, `MemberController`, `MemberService`, `Member/dto/*`, `DuplicateMemberException`, security·jwt 의존성 | 회원가입·로그인·JWT. 실험 API 는 인증을 쓰지 않는다. 요청마다 JWT 필터가 회원 SELECT 를 해서 DB 측정에도 섞인다 |
| `IssueCouponController`, `CouponController` | 같은 서비스 3개를 6개 경로로 노출. `/lab` 이 대체 |
| `CouponReadRequest`, `CouponReadResponse`, `IssueCouponRequest`, `CouponService.findCouponById`, `MemberRepository.findByName` | 호출처 없음(또는 위 컨트롤러만 사용) |
| `RedisConfig` | 쓰이지 않는 `RedisTemplate<String, Object>` 빈, `spring.data.redis.database`·`password` 를 무시함. Boot 자동 설정으로 대체 |
| `MemberServiceTest`, `IssueCouponServiceTest`, `IssueCouponControllerIntegrationTest` | 회원가입 테스트 / `RedisIssueCouponServiceTest` 와 중복 / `LabControllerTest` 가 대체 |
| `performance/scripts/generate-jwt-tokens.js`, `performance/sql/reset-and-seed.sql` | 실험 API 의 회원·쿠폰 준비로 대체 |

### 삭제 (2026-10-17)

| 대상 | 이유 |
|---|---|
| `@Retry`, `OptimisticLockRetryAspect` | 호출처 없음. 최대 1000회 × 100ms 고정 지연. 2단계 `OptimisticIssueCouponService.issueWithRetry` 가 대체 |

### 삭제 (2026-10-19)

| 대상 | 이유 |
|---|---|
| `CouponIssuedEvent`, `CouponIssueEventListener` | 요청 경로에 DB 트랜잭션이 없어 `@TransactionalEventListener` 가 지킬 "커밋 뒤" 가 없다. `@Async` 메서드 하나로 대체 (4단계 문서 2절) |
| `CouponIssueMetrics`, `CouponIssueResult`, `CouponIssueStrategy` 의 지표 태그 | 이벤트 방식에서만 기록. 결과 API 가 대체 |
| `CouponIssuedRedisRepository.checkAndRegister` | 이벤트 방식만 쓰던 MULTI/EXEC 예약. 4~6단계는 Lua 예약 |
| Grafana 패널 4개(`coupon_issue_*`) | 위 지표 삭제로 데이터가 없다 |
| 작업 전부터 있던 미커밋 변경 3개 파일(`EventIssueCouponService`, `CouponIssueEventListener`, `EventIssueCouponServiceTest`) | 재작성 전에 `git stash` 로 보관(`stash@{0}`). `git stash show -p --include-untracked stash@{0}` 로 볼 수 있다 |
