# 1단계: 명시적 락 없이 격리 수준 비교 (READ COMMITTED · REPEATABLE READ · SERIALIZABLE)

실행 원본
- 2026-10-15 RR·SERIALIZABLE: [JUnit](results/2026-10-15-stage1-junit.md), [k6](results/2026-10-15-stage1-k6.md)
- 2026-10-17 변형 실험: [READ COMMITTED · 중복 확인 SELECT 제거](results/2026-10-17-stage1-variations-junit.md), [데드락 감지 끄기 · 락 대기 시간](results/2026-10-17-stage1-lock-settings.md)

## 1. 확인할 질문과 예상

질문
- 같은 로직(조회 → 수량 확인 → 엔티티 값 증가 → 저장)을 격리 수준만 바꿔 실행하면 공통 기준 4가지를 각각 지키는가?
- 지킨다면 무엇으로 지키는가? 그 대가는 무엇인가?
- 먼저 도착한 요청이 당첨되는가?

실행 전 예상
- RR: 일반 SELECT 는 MVCC 스냅샷을 읽으므로 두 트랜잭션이 같은 `issued_count` 를 보고 같은 값으로 덮어쓴다(갱신 손실). 재고 경계에서는 초과 발급. 같은 회원 중복은 UNIQUE 제약이 막을 것.
- SERIALIZABLE: 일반 SELECT 가 공유 락(S) 읽기가 되어 갱신 손실은 막겠지만, 두 트랜잭션이 S 락을 쥔 채 X 락을 원하면 데드락이 날 것.

변형 실험의 실행 전 예상 (4-4 에서 결과와 비교)
- READ COMMITTED: RR 과 같은 결과. 이 로직은 쿠폰을 한 번만 읽고 Java 가 계산한 값으로 덮어쓰므로, 스냅샷을 트랜잭션 첫 읽기에 잡든(RR) 문장마다 잡든(RC) 갱신 손실은 그대로일 것.
- 중복 확인 SELECT 제거: SERIALIZABLE 의 갭 락이 사라져 데드락 위치가 coupons 행의 S→X 로 옮겨갈 것.
- 데드락 감지 끄기: 데드락 대신 `innodb_lock_wait_timeout` 까지 기다린 뒤 한쪽이 `LOCK_WAIT_TIMEOUT` 으로 롤백되고, 다른 쪽은 이어서 진행할 것.
- 락 대기 시간을 1초로 줄이기(감지는 켬): 변화 없을 것. SERIALIZABLE 은 데드락 감지가 먼저 끊고, RR 의 UPDATE 대기는 1초보다 훨씬 짧다.

## 2. 구현과 실행 순서

구현: [`IsolationIssueCouponService`](../../src/main/java/couponToy/CouponToyProject/CouponIssue/service/IsolationIssueCouponService.java)

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public IssueCouponResponse issueWithReadCommitted(Long memberId, Long couponId, boolean duplicateCheck) { return issue(memberId, couponId, duplicateCheck); }

@Transactional(isolation = Isolation.REPEATABLE_READ)
public IssueCouponResponse issueWithRepeatableRead(Long memberId, Long couponId, boolean duplicateCheck) { return issue(memberId, couponId, duplicateCheck); }

@Transactional(isolation = Isolation.SERIALIZABLE)
public IssueCouponResponse issueWithSerializable(Long memberId, Long couponId, boolean duplicateCheck) { return issue(memberId, couponId, duplicateCheck); }
```

`@Version`, `FOR UPDATE`, Redis, 조건부 UPDATE(`increaseIssuedCount`) 는 쓰지 않는다. 수량 판단은 `Coupon.increaseIssueAmount()` 가 Java 메모리에서 한다. `duplicateCheck=false` 는 3번 SELECT 만 건너뛰는 변형이다(실험 API 기본값은 true).

요청 하나가 보내는 SQL (테스트 로그에서 확인한 Hibernate 출력 순서)

| 순서 | SQL | RC 에서 | RR 에서 | SERIALIZABLE 에서 |
|---|---|---|---|---|
| 1 | `select ... from members where member_id=?` | 문장마다 새 스냅샷, 락 없음 | 트랜잭션 첫 읽기 시점 스냅샷, 락 없음 | S 락 (InnoDB 규칙, 직접 관찰하지 않음) |
| 2 | `select ... from coupons where coupon_id=?` | 문장마다 새 스냅샷, 락 없음 | 스냅샷 읽기, 락 없음 | **S 락 (`S,REC_NOT_GAP`)** |
| 3 | `select issued_id from coupon_issues where member_id=? and coupon_id=? limit ?` | 문장마다 새 스냅샷 | 스냅샷 읽기 | **UNIQUE 인덱스에 S 넥스트키(갭) 락** |
| - | Java: `issuedCount >= totalCount` 면 SOLD_OUT, 아니면 `issuedCount++` | | | |
| 4 | `insert into coupon_issues ...` (IDENTITY 라 즉시 실행) | insert intention 락. 같은 키가 이미 있으면 그 행에 `S` 락 대기 | 같음 | insert intention 락 → 다른 트랜잭션의 갭 락과 충돌 |
| 5 | 커밋 직전 flush: `update coupons set ..., issued_count=? ... where coupon_id=?` | X 락. **읽은 값 + 1 을 그대로 씀** | 같음 | X 락. 다른 트랜잭션의 S 락과 충돌 |
| 6 | commit | | | |

트랜잭션 경계 확인
- `LabService` → `IsolationIssueCouponService` 는 빈 간 호출이라 프록시를 지난다(`AopUtils.isAopProxy` = true). 실제 로직 `issue()` 는 private 이라 단독으로는 트랜잭션을 만들 수 없다.
- 락 대기 중인 트랜잭션을 `information_schema.innodb_trx` 에서 직접 읽어 `trx_isolation_level` 이 각각 `READ COMMITTED` / `REPEATABLE READ` / `SERIALIZABLE` 인 것을 확인했다. 요청마다 `trx_id` 가 다르다(독립 트랜잭션). 요청마다 coupons SELECT 가 다시 나가므로 영속성 컨텍스트도 공유하지 않는다.

실행 순서를 고정하는 방법 (테스트 하네스, 실험 대상 아님)
- 테스트가 별도 커넥션으로 `SELECT ... FOR UPDATE` 를 걸어 쿠폰 행을 잡는다 → 요청 2개를 출발시킨다 → `sys.innodb_lock_waits` 에서 2건이 모두 대기 중인 것을 확인한다 → 놓아준다.
- RC·RR 의 일반 SELECT 는 이 락에 막히지 않으므로 두 요청 모두 읽기와 INSERT 를 마치고 5번 UPDATE 에서 멈춘다. "둘 다 읽은 뒤에 쓴다" 는 순서가 매번 재현된다.
- SERIALIZABLE 은 2번 SELECT 에서 멈춘다. 놓아주면 두 요청이 동시에 S 락을 얻는다.
- 주의: `innodb_trx` 는 0.1초 캐시라서 그보다 자주 조회하면 계속 옛 값이 나온다. 폴링 간격을 200ms 로 둔 이유다.

## 3. 직접 실행할 명령과 바꿔볼 조건

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # Java 17 로 실행
docker compose up -d mysql redis

# 정합성: 순서 고정 재현 + 동시 요청 기록 (테스트 DB, Redis db 1). RC 와 중복 확인 제거 변형 포함
./gradlew test --tests '*IsolationIssueCouponServiceTest'
open build/lab/stage1-isolation.md

# 데드락 감지 끄기 · 락 대기 시간 (약 2분). MySQL 전역 설정을 바꾸므로 환경변수를 줄 때만 실행되고, 끝나면 원래 값으로 되돌린다
LAB_CHANGE_MYSQL_GLOBALS=true ./gradlew test --tests '*IsolationLockSettingsExperimentTest'
open build/lab/stage1-lock-settings.md
```

케이스와 기대 결과는 각 테스트의 `@CsvSource` 표에 있다. 행을 추가하거나 값을 바꿔 실행하면 된다.

HTTP 로 직접 보내 보기 (사용 중인 DB 를 건드리지 않도록 별도 스키마와 Redis db 를 쓴다)

```bash
SERVER_PORT=18080 \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
SPRING_DATA_REDIS_DATABASE=2 SPRING_JPA_SHOW_SQL=false SPRING_JPA_PROPERTIES_HIBERNATE_SHOW_SQL=false \
./gradlew bootRun

# 다른 터미널. setup 에서 회원·쿠폰을 새로 만들고 teardown 에서 결과 API 를 출력한다.
BASE_URL=http://localhost:18080 METHOD=SERIALIZABLE SCENARIO=burst \
TOTAL_COUNT=100 MEMBERS=1000 ITERATIONS=1000 VUS=100 WARMUP=200 \
k6 run performance/k6/coupon-issue.js
```

curl 로 한 건씩

```bash
curl -XPOST localhost:18080/lab/members -H 'Content-Type: application/json' -d '{"count": 10}'
curl -XPOST localhost:18080/lab/coupons -H 'Content-Type: application/json' -d '{"name": "s1", "totalCount": 1}'
curl -XPOST localhost:18080/lab/coupons/1/issue -H 'Content-Type: application/json' -d '{"memberId": 1, "method": "REPEATABLE_READ"}'
curl -XPOST localhost:18080/lab/coupons/1/issue -H 'Content-Type: application/json' -d '{"memberId": 2, "method": "READ_COMMITTED", "duplicateCheck": false}'
curl localhost:18080/lab/coupons/1/result
```

바꿔볼 조건

| 조건 | 바꾸는 법 | 실행 여부 |
|---|---|---|
| 동시에 열리는 트랜잭션 수 | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5/20/50` 을 테스트나 bootRun 앞에 붙인다 | 실행함 (아래 표) |
| 요청 수, 동시성, 재고, 중복 비율 | k6 `ITERATIONS`, `VUS`, `TOTAL_COUNT`, `SCENARIO=duplicate DUPLICATE_USERS=` | RR/SER 각 3개 실험 실행함 |
| 데드락 감지 끄기 | `IsolationLockSettingsExperimentTest` (`SET GLOBAL innodb_deadlock_detect = OFF`) | 실행함 (4-4) |
| 락 대기 시간 | 같은 테스트 (`SET GLOBAL innodb_lock_wait_timeout = 3/50`, 감지 ON 에서 1) | 실행함 (4-4) |
| 중복 확인 SELECT 제거 | `duplicateCheck=false` (테스트 `@CsvSource`, API 요청 바디, k6 `DUPLICATE_CHECK=false`) | 실행함 (4-4) |
| READ COMMITTED | `method: READ_COMMITTED` (k6 `METHOD=READ_COMMITTED`) | JUnit 실행함 (4-4), HTTP 는 실행 안 함 |

## 4. 관찰 결과와 원인

### 4-1. 순서를 고정한 두 트랜잭션 (4회 반복, 매번 같은 결과)

| 경우 | RR | SERIALIZABLE |
|---|---|---|
| 재고 1, 서로 다른 회원 2명 | 둘 다 성공, 행 2 / `issued_count` 1 → **초과 발급 + 갱신 손실** | 1건 성공, 1건 `DEADLOCK`, 행 1 / 1 |
| 재고 10, 서로 다른 회원 2명 | 둘 다 성공, 행 2 / 1 → **갱신 손실** | 1건 성공, 1건 `DEADLOCK` (재고가 남았는데 실패) |
| 재고 10, 같은 회원 2번 | 1건 성공, 1건 `UNIQUE_VIOLATION` | 1건 성공, 1건 `DEADLOCK` |
| 잠금 해제 직전 대기 위치 | `update coupons` 에서 `X,REC_NOT_GAP` 대기 (같은 회원이면 두 번째는 UNIQUE 인덱스 `S` 대기) | `select ... from coupons` 에서 `S,REC_NOT_GAP` 대기 |
| 실패 예외 | `DataIntegrityViolationException` → HTTP 409 | `CannotAcquireLockException`(MySQL 1213) → HTTP 503 |

RR 원인: 두 트랜잭션이 스냅샷에서 `issued_count=0` 을 읽는다. UPDATE 는 X 락 때문에 차례로 실행되지만, 쓰는 값은 SQL 이 아니라 Java 가 계산한 `0 + 1` 이다. 뒤의 트랜잭션이 앞의 결과를 덮어쓴다.

SERIALIZABLE 원인: 관찰된 데드락은 쿠폰 행이 아니라 `coupon_issues` 의 UNIQUE 인덱스에서 났다. 중복 확인 SELECT 가 "없는 행" 을 S 락으로 읽으면서 인덱스 끝(supremum)까지의 갭을 잠갔고, 두 트랜잭션이 서로의 갭에 INSERT 하려다 막혔다.

```text
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index uk_coupon_issue_coupon_member of table `coupon_issues` ... lock mode S   (supremum)
*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index uk_coupon_issue_coupon_member ... lock_mode X insert intention waiting
*** (2) 도 같은 모양
*** WE ROLL BACK TRANSACTION (2)
```

갭 락이 없어도 두 트랜잭션은 coupons 행에 S 락을 쥔 채 커밋 때 X 락이 필요하다. 중복 확인 SELECT 를 빼고 실행해 보니 실제로 데드락이 coupons 행으로 옮겨갔다(4-4).

SERIALIZABLE 은 lock-free 가 아니다. InnoDB 는 autocommit 이 꺼진 트랜잭션의 일반 SELECT 를 `FOR SHARE` 로 바꾼다. 정합성은 이 공유 락과 데드락 감지 후 롤백으로 지켜진다.

### 4-2. 동시 요청 (JUnit, 요청 50건, 스레드 50, Hikari 10, 3회 중 대표값)

| 시나리오 | RR | SERIALIZABLE |
|---|---|---|
| 재고 10, 회원 50명 | **50건 모두 발급**, `issued_count` 5~6 | 6건 발급, 44건 `DEADLOCK`. 먼저 온 10명 중 당첨 1명 |
| 재고 50, 회원 50명 | 50건 발급, `issued_count` 5~6 | **6건만 발급**, 44건 `DEADLOCK` |
| 재고 50, 회원 10명 × 5번 | 10건 발급, 중복 거절 중 13~18건은 앱 확인이 아니라 UNIQUE 가 막음 | 5~6건 발급, 나머지는 `DUPLICATE`/`DEADLOCK`. 회원 4~5명은 5번 모두 실패 |

Hikari 풀 크기를 바꾼 결과 (같은 테스트, 재고 50 / 회원 50명 시나리오)

| 최대 풀 | RR 발급 행 / issued_count | SERIALIZABLE 발급 건수 / 데드락 |
|---:|---|---|
| 5 | 50 / 10~11 | 10~14 / 36~40 |
| 10 | 50 / 5~6 | 6 / 44 |
| 20 | 50 / 3 | 3 / 47 |
| 50 | 50 / 1 | 1 / 49 |

커넥션 풀 크기가 곧 "같은 쿠폰 행을 동시에 붙잡는 트랜잭션 수" 다. RR 은 동시에 읽는 트랜잭션이 많을수록 `issued_count` 가 덜 올라가고(풀 50 이면 50명이 모두 0 을 읽었다), SERIALIZABLE 은 동시에 열린 트랜잭션 묶음마다 대략 1건만 살아남는다.

### 4-3. HTTP (k6, 회원 1000명, VU 100, 요청 1000건, Hikari 10)

| 실험 | 방식 | 결과 | 발급 행 / issued_count | 먼저 온 N명 중 당첨 | HTTP p95 | 접수 / 발급 완료 req/s |
|---|---|---|---|---:|---:|---|
| 재고 100 | RR | 성공 997, 품절 3 | **997** / 100 | 100/100 | 345ms | 376 / 375 |
| 재고 100 | SER | 성공 100, 품절 53, 데드락 847 | 100 / 100 | **12/100** | 304ms | 518 / 52 |
| 재고 1000 | RR | 성공 1000 | 1000 / **101** | 1000/1000 | 279ms | 433 / 433 |
| 재고 1000 | SER | 성공 103, 데드락 897 | **103** / 103 | 103/1000 | 228ms | 562 / 58 |
| 회원 100명 × 10번 | RR | 성공 100, 중복 900 | 100 / 10 | 100/100 | 182ms | 1087 / 109 |
| 회원 100명 × 10번 | SER | 성공 98, 중복 502, 데드락 400 | 98 / 98 | 98/100 | 312ms | 532 / 52 |

- 처리량은 순위를 매기지 않는다. RR 의 발급 완료 처리량은 틀린 발급을 포함한 숫자이고, SERIALIZABLE 의 접수 처리량이 높은 것은 데드락으로 빨리 실패한 요청이 많아서다.
- HTTP p95 와 서버 측 p95(진입 → 커밋 후 반환)는 1~5ms 차이였다. 시간 대부분은 커넥션 풀 대기와 락 대기다(RR 락 대기 시간 합 18.6초, SER 4.1초).

선착순 관찰
- 도착 순서는 `LabService.issue()` 진입 시 증가시킨 번호, 당첨은 DB 커밋 성공, 완료 순서는 커밋 후 반환된 순서로 기록했다.
- RR 은 거의 모두 당첨되므로 순서를 말할 수 없다. SERIALIZABLE 은 데드락 희생자가 도착 순서와 무관하게 정해져 먼저 온 100명 중 12명만 당첨됐다.
- 두 방식 모두 성공한 요청끼리도 도착 순서와 커밋 순서가 다르게 끝난 경우가 있었다(19~51건).

### 4-4. 변형 실험 (2026-10-17, JUnit, Hikari 10)

순서 고정 14개 케이스를 3번 실행해 매번 같은 결과가 나왔다. 동시 요청은 회차당 50건, 3회차 범위다.

**READ COMMITTED: 예상대로 RR 과 같았다**

| 경우 | RC | RR (같은 실행) |
|---|---|---|
| 순서 고정, 재고 1, 서로 다른 회원 2명 | 둘 다 성공, 행 2 / `issued_count` 1. `update coupons` 에서 `X,REC_NOT_GAP` 대기 | 같음 |
| 순서 고정, 같은 회원 2번 | 1건 성공, 1건 `UNIQUE_VIOLATION`. 두 번째 INSERT 가 UNIQUE 인덱스에서 `S` 락 대기 | 같음 |
| 동시: 재고 10, 회원 50명 | 50건 발급, `issued_count` 6 | 50건, 6 |
| 동시: 재고 50, 회원 50명 | 50건 발급, `issued_count` 6 | 50건, 5 |
| 동시: 회원 10명 × 5번 | 10건, 앱 중복 확인 25~29건 / UNIQUE 11~15건 | 22~27 / 13~18 |

- RC 는 문장마다 최신 커밋을 읽지만, 이 로직은 쿠폰을 한 번만 읽고 그 값 + 1 을 쓰기 때문에 갱신 손실과 초과 발급이 그대로다.
- 중복 키 검사에서 RC 도 `S` 락(갭 포함 표기)으로 기다렸다. RC 라고 INSERT 의 중복 검사 락이 사라지지는 않는다.

**중복 확인 SELECT 제거: 데드락 위치가 coupons 행으로 옮겨갔다**

```text
*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index PRIMARY of table `coupons` ... lock mode S locks rec but not gap
*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index PRIMARY of table `coupons` ... lock_mode X locks rec but not gap waiting
*** (2) 도 같은 모양 (둘 다 update coupons 실행 중)
*** WE ROLL BACK TRANSACTION (2)
```

| 경우 | SERIALIZABLE, 중복 확인 있음 | SERIALIZABLE, 중복 확인 없음 |
|---|---|---|
| 순서 고정, 서로 다른 회원 2명 | 1 성공 + 1 데드락. UNIQUE 인덱스 갭에서 | 1 성공 + 1 데드락. **coupons PRIMARY 의 S→X 에서** |
| 순서 고정, 같은 회원 2번 | 1 성공 + 1 데드락 | 1 성공 + 1 데드락. 한쪽은 UNIQUE 인덱스 `S`, 다른 쪽은 coupons `X` 를 기다림 |
| 동시: 재고 10, 회원 50명 | 6 발급 / 데드락 44 | 8 발급 / 데드락 42 |
| 동시: 재고 50, 회원 50명 | 5~6 발급 / 데드락 44~45 | 6~8 발급 / 데드락 42~44 |
| 동시: 회원 10명 × 5번 | 6 발급, 앱 중복 확인 8~12 | 6~7 발급, 중복은 모두 UNIQUE(7~12) |

- 갭 락은 데드락이 터지는 위치를 정했을 뿐이다. 근본 원인은 "S 락을 쥔 채 X 락으로 올리는" 구조이고, 중복 확인을 빼도 동시에 열린 트랜잭션 묶음마다 1~2건만 살아남는 모양은 같았다.
- RR 에서 중복 확인을 빼면 같은 회원 중복 40건이 모두 `UNIQUE_VIOLATION` 이 됐다(앱 확인 0건). 결과 행 수는 같다. 앱의 중복 확인은 DB 왕복을 하나 더 쓰는 대신, 대부분의 중복을 INSERT 전에 409 로 돌려보내는 역할만 한다.

**데드락 감지 끄기: 예상과 달리 둘 다 실패했다**

| 경우 | `innodb_lock_wait_timeout` | 결과 | 쿠폰 행 잠금을 푼 뒤 끝날 때까지 | 발급 |
|---|---:|---|---|---:|
| SERIALIZABLE, 순서 고정, 재고 10, 서로 다른 회원 2명 | 3 | `LOCK_WAIT_TIMEOUT` × 2 | 3067ms, 3067ms | 0 |
| 같음 | 50 | `LOCK_WAIT_TIMEOUT` × 2 | 50203ms, 50203ms | 0 |
| SERIALIZABLE, 동시 50건, 재고 50 | 3 | **50건 모두 `LOCK_WAIT_TIMEOUT`** | p50 9.1초, 전체 15.2초 | **0** |
| RR, 동시 50건, 재고 50 | 3 | 50건 성공, `issued_count` 5 | 전체 0.2초 | 50 |

- 감지가 꺼지면 서로를 기다리는 두 트랜잭션은 누구도 먼저 포기하지 않는다. 두 요청은 잠금 해제와 동시에 출발했고 종료 시각이 ms 단위까지 같았다. 대기 시작 시각은 따로 재지 않았지만, 거의 같은 시점에 서로를 기다리기 시작해 함께 시간 초과된 것으로 본다. 감지가 켜져 있을 때 1건이라도 살리던 것과 달리, 이번에는 아무도 발급받지 못했다.
- 시간 초과 예외는 데드락(`CannotAcquireLockException`)과 다른 `PessimisticLockingFailureException` 으로 올라왔고, 같은 핸들러가 503 으로 돌려준다. `innodb_rollback_on_timeout=OFF` 라서 InnoDB 는 해당 문장만 되돌린다. 트랜잭션 전체 롤백은 예외를 받은 Spring 이 했다.
- 동시 요청에서는 커넥션 10개가 3초씩 묶인 채 묶음마다 전부 실패했다. 50건 / 풀 10 = 5묶음 × 약 3초 = 15초. 감지를 끄면 실패가 사라지지 않고, 늦게 더 많이 온다.
- RR 은 원래 데드락이 없어서 결과가 같았다.

**락 대기 시간 1초 (감지 켬): 예상대로 변화 없음**

| 방식 | 결과 (3회) | 전체 소요 |
|---|---|---|
| SERIALIZABLE, 동시 50건, 재고 50 | 6 발급 / 데드락 44, 타임아웃 0 | 88~98ms |
| RR, 동시 50건, 재고 50 | 50 발급, `issued_count` 5, 타임아웃 0 | 128~140ms |

- SERIALIZABLE 은 데드락 감지가 ms 안에 끊고, RR 의 UPDATE 대기는 요청 전체 p95 가 140ms 이하라 1초에 닿지 않는다. 이 부하에서는 대기 시간이 결과를 정하지 않는다.

측정 중 발견한 문제
- 설정을 바꿀 때 Hikari 커넥션을 버리고 바로 실행했더니, 1회차만 커넥션 생성을 기다리느라 동시성이 낮아졌다. RR `issued_count` 13·17, SERIALIZABLE 성공 17 처럼 튀었다. 풀을 최대 크기까지 미리 채운 뒤 다시 실행해 1회차도 2·3회차와 같아진 것을 확인했다. 1차 실행 기록도 원본에 남겼다.
- 동시 요청 50건이 모두 `LOCK_WAIT_TIMEOUT` 이었는데, InnoDB `lock_timeouts` 카운터 변화량은 회차에 따라 49 로 1 적었다. 원인은 확인하지 못했다.

## 5. 보장하는 범위와 해결하지 못한 문제

| 기준 | READ COMMITTED | REPEATABLE READ | SERIALIZABLE |
|---|---|---|---|
| 사용자당 최대 1장 | 지켜짐. **UNIQUE 제약** 덕분 | 지켜짐. 단 앱의 중복 확인이 아니라 **UNIQUE 제약** 덕분 | 지켜짐 (S 갭 락 + UNIQUE) |
| 발급 건수 ≤ 재고 | **깨짐** (재고 10 에 50장, JUnit) | **깨짐** (재고 100 에 997장) | 지켜짐 |
| DB 발급 행 = `issued_count` | **깨짐** (갱신 손실) | **깨짐** (갱신 손실) | 지켜짐 |
| 유효 요청이 충분하면 재고만큼 발급 | 초과 발급이라 의미 없음 | 초과 발급이라 의미 없음 | **깨짐** (재고 1000 에 103장, 재시도 없음. 데드락 감지를 끄면 0장) |
| 먼저 온 요청이 당첨 | 판단 불가 | 판단 불가 | **보장 안 됨** (12/100) |

해결하지 못한 문제
- SERIALIZABLE 은 정합성을 데드락 롤백으로 지키므로, 동시에 붙는 트랜잭션이 많을수록 실패가 늘어난다. 재시도를 붙이면 성공 건수는 늘겠지만 순서는 더 섞인다(이번 단계에서는 재시도 없이 측정).
- 갭 락 범위는 인덱스에 이미 있는 이웃 레코드에 따라 달라진다. 서로 다른 쿠폰의 요청끼리도 같은 갭을 잠글 수 있다(이번에는 측정하지 않음).
- 같은 회원의 반복 요청이 모두 데드락으로 실패한 경우가 있었다. 특정 요청이 계속 지는지는 2단계 재시도 실험에서 본다.

## 6. 다음 단계로 넘어가는 이유

격리 수준만으로는 "언제, 어떤 행에, 어떤 락을 거는지" 를 고를 수 없다. RC·RR 은 아무 락도 걸지 않아 틀렸고, SERIALIZABLE 은 모든 읽기에 공유 락을 걸어 맞았지만 데드락으로 요청을 버렸다.

변형 실험으로 확인한 것: 데드락의 원인은 S 락을 쥔 채 X 락으로 올리는 구조다. 중복 확인을 빼면 데드락이 터지는 위치만 바뀌고, 감지를 끄면 더 늦게 더 많이 실패한다. 락 대기 시간은 이 부하에서 결과를 정하지 않았다.

2단계에서는 같은 발급 조건에서 락을 직접 고른다.
- 비관적 락: 처음부터 X 락(`FOR UPDATE`)으로 읽어 S→X 경합 자체를 없애면 데드락 대신 줄서기가 되는가?
- 낙관적 락: 락 없이 읽고 커밋 시 버전으로 충돌을 감지하면, 재시도 횟수와 순서는 어떻게 되는가?
