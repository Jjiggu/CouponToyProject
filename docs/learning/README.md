# 선착순 쿠폰 발급: 동시성 제어와 비동기 처리 학습

한 단계씩 구현하고, 직접 실행해서 확인한 것만 적는다. 이전 문서(`docs/performance`, `docs/coupon-performance-test`, `docs/overview.md` 등)는 JWT 인증과 이전 엔드포인트 기준이며 결과 보존용이다. 그 수치를 새 구현의 성과로 쓰지 않는다.

| 단계 | 주제 | 상태 |
|---|---|---|
| 1 | [명시적 락 없이 격리 수준 비교](01-isolation-level.md) | 구현·실행 완료 (변형 실험 포함) |
| 2 | [낙관적 락과 비관적 락](02-optimistic-pessimistic.md) | 구현·실행 완료 |
| 3 | [Redis 예약: SET 명령만 / MULTI/EXEC / WATCH / Lua](03-redis-transaction-lua.md) | 구현·실행 완료 |
| 4 | Spring @Async | 계획 |
| 5 | Redis Pub/Sub | 계획 |
| 6 | Redis Streams | 계획 |

## 공통 검증 기준

결과 API(`GET /lab/coupons/{id}/result`)가 매번 계산한다.

| 기준 | 결과 필드 |
|---|---|
| 쿠폰별 사용자당 최대 1장 | `checks.atMostOncePerMember` (`coupon_issues` 행 수 = 회원 수) |
| 실제 발급 건수 ≤ 전체 수량 | `checks.notOverIssued` |
| DB 발급 행 = `issued_count` | `checks.issuedCountMatchesRows` |
| 유효 요청이 충분하면 수량만큼 발급 | `checks.fullyIssued` (유효 요청 < 수량이면 `null`) |
| 접수·완료·실패·미완료 구분 | `requests` (진입점에서 본 결과), `issueRows` (DB에서 본 완료). 비동기 단계부터 둘이 달라진다 |

결과 분류(`IssueOutcome`): `SUCCESS`(커밋 완료), `ACCEPTED`(접수만), 거절 `SOLD_OUT`·`DUPLICATE`·`UNIQUE_VIOLATION`(HTTP 409), 트랜잭션 실패 `DEADLOCK`·`LOCK_WAIT_TIMEOUT`·`OPTIMISTIC_CONFLICT`(HTTP 503), `NOT_FOUND`, `ERROR`.

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
- 현재 구현: 트랜잭션 없이 이벤트 발행 → `@TransactionalEventListener(fallbackExecution = true)` 가 트랜잭션이 없어 바로 실행 → `@Async("couponIssueEventExecutor")` (core 4, max 8, queue 50000, 거부 정책 기본값 Abort) → `TransactionTemplate(REQUIRES_NEW)` 로 DB 확정. 작업 실패 시 로그만 남고 Redis 예약은 되돌리지 않는다(미커밋 변경에서 SREM 제거).
- HTTP 202 접수와 DB 완료를 구분해 센다. 큐 포화, 작업 예외, DB 실패, 프로세스 강제 종료 때 작업이 실행되거나 사라지는 위치를 확인한다.

### 5단계: Redis Pub/Sub
- 같은 코드베이스를 실행 설정으로 발행자/소비자 프로세스로 나눈다.
- 정상 전달, 구독자 없음, 구독자 중단·재시작, 수신 후 DB 실패, 구독자 2개가 같은 메시지를 각각 받는 경우.
- PUBLISH 성공이나 구독자 수를 완료로 보지 않는다. 별도 영속 큐나 복구 장치를 만들지 않고, 감지·보상 가능한 실패와 근거 없이 사라지는 유실을 나눠 적는다.

### 6단계: Redis Streams
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
| `CouponIssuedRedisRepository.checkAndRegister`, `increaseIssuedCount` | 4단계 기준선(`EventIssueCouponService`)이 그대로 쓴다 |
| `EventIssueCouponService`, `CouponIssueEventListener`, `AsyncConfig` | 4단계 기준선 (미커밋 변경 그대로) |
| `CouponIssueMetrics`, `monitoring/` | Grafana 대시보드가 사용 |
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
