# 1단계: 명시적 락 없이 격리 수준 비교 (REPEATABLE READ vs SERIALIZABLE)

실행 원본: [JUnit](results/2026-10-15-stage1-junit.md), [k6](results/2026-10-15-stage1-k6.md)

## 1. 확인할 질문과 예상

질문
- 같은 로직(조회 → 수량 확인 → 엔티티 값 증가 → 저장)을 격리 수준만 바꿔 실행하면 공통 기준 4가지를 각각 지키는가?
- 지킨다면 무엇으로 지키는가? 그 대가는 무엇인가?
- 먼저 도착한 요청이 당첨되는가?

실행 전 예상
- RR: 일반 SELECT 는 MVCC 스냅샷을 읽으므로 두 트랜잭션이 같은 `issued_count` 를 보고 같은 값으로 덮어쓴다(갱신 손실). 재고 경계에서는 초과 발급. 같은 회원 중복은 UNIQUE 제약이 막을 것.
- SERIALIZABLE: 일반 SELECT 가 공유 락(S) 읽기가 되어 갱신 손실은 막겠지만, 두 트랜잭션이 S 락을 쥔 채 X 락을 원하면 데드락이 날 것.

## 2. 구현과 실행 순서

구현: [`IsolationIssueCouponService`](../../src/main/java/couponToy/CouponToyProject/CouponIssue/service/IsolationIssueCouponService.java)

```java
@Transactional(isolation = Isolation.REPEATABLE_READ)
public IssueCouponResponse issueWithRepeatableRead(Long memberId, Long couponId) { return issue(memberId, couponId); }

@Transactional(isolation = Isolation.SERIALIZABLE)
public IssueCouponResponse issueWithSerializable(Long memberId, Long couponId) { return issue(memberId, couponId); }
```

`@Version`, `FOR UPDATE`, Redis, 조건부 UPDATE(`increaseIssuedCount`) 는 쓰지 않는다. 수량 판단은 `Coupon.increaseIssueAmount()` 가 Java 메모리에서 한다.

요청 하나가 보내는 SQL (테스트 로그에서 확인한 Hibernate 출력 순서)

| 순서 | SQL | RR 에서 | SERIALIZABLE 에서 |
|---|---|---|---|
| 1 | `select ... from members where member_id=?` | 스냅샷 읽기, 락 없음 | S 락 (InnoDB 규칙, 직접 관찰하지 않음) |
| 2 | `select ... from coupons where coupon_id=?` | 스냅샷 읽기, 락 없음 | **S 락 (`S,REC_NOT_GAP`)** |
| 3 | `select issued_id from coupon_issues where member_id=? and coupon_id=? limit ?` | 스냅샷 읽기 | **UNIQUE 인덱스에 S 넥스트키(갭) 락** |
| - | Java: `issuedCount >= totalCount` 면 SOLD_OUT, 아니면 `issuedCount++` | | |
| 4 | `insert into coupon_issues ...` (IDENTITY 라 즉시 실행) | insert intention 락 | insert intention 락 → 다른 트랜잭션의 갭 락과 충돌 |
| 5 | 커밋 직전 flush: `update coupons set ..., issued_count=? ... where coupon_id=?` | X 락. **읽은 값 + 1 을 그대로 씀** | X 락. 다른 트랜잭션의 S 락과 충돌 |
| 6 | commit | | |

트랜잭션 경계 확인
- `LabService` → `IsolationIssueCouponService` 는 빈 간 호출이라 프록시를 지난다(`AopUtils.isAopProxy` = true). 실제 로직 `issue()` 는 private 이라 단독으로는 트랜잭션을 만들 수 없다.
- 락 대기 중인 트랜잭션을 `information_schema.innodb_trx` 에서 직접 읽어 `trx_isolation_level` 이 각각 `REPEATABLE READ` / `SERIALIZABLE` 인 것을 확인했다. 요청마다 `trx_id` 가 다르다(독립 트랜잭션). 요청마다 coupons SELECT 가 다시 나가므로 영속성 컨텍스트도 공유하지 않는다.

실행 순서를 고정하는 방법 (테스트 하네스, 실험 대상 아님)
- 테스트가 별도 커넥션으로 `SELECT ... FOR UPDATE` 를 걸어 쿠폰 행을 잡는다 → 요청 2개를 출발시킨다 → `sys.innodb_lock_waits` 에서 2건이 모두 대기 중인 것을 확인한다 → 놓아준다.
- RR 의 일반 SELECT 는 이 락에 막히지 않으므로 두 요청 모두 읽기와 INSERT 를 마치고 5번 UPDATE 에서 멈춘다. "둘 다 읽은 뒤에 쓴다" 는 순서가 매번 재현된다.
- SERIALIZABLE 은 2번 SELECT 에서 멈춘다. 놓아주면 두 요청이 동시에 S 락을 얻는다.
- 주의: `innodb_trx` 는 0.1초 캐시라서 그보다 자주 조회하면 계속 옛 값이 나온다. 폴링 간격을 200ms 로 둔 이유다.

## 3. 직접 실행할 명령과 바꿔볼 조건

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # Java 17 로 실행
docker compose up -d mysql redis

# 정합성: 순서 고정 재현 + 동시 요청 기록 (테스트 DB, Redis db 1)
./gradlew test --tests '*IsolationIssueCouponServiceTest'
open build/lab/stage1-isolation.md
```

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
curl localhost:18080/lab/coupons/1/result
```

바꿔볼 조건

| 조건 | 바꾸는 법 | 실행 여부 |
|---|---|---|
| 동시에 열리는 트랜잭션 수 | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5/20/50` 을 테스트나 bootRun 앞에 붙인다 | 실행함 (아래 표) |
| 요청 수, 동시성, 재고, 중복 비율 | k6 `ITERATIONS`, `VUS`, `TOTAL_COUNT`, `SCENARIO=duplicate DUPLICATE_USERS=` | RR/SER 각 3개 실험 실행함 |
| 데드락 감지 끄기 | `SET GLOBAL innodb_deadlock_detect = OFF` → 데드락 대신 `innodb_lock_wait_timeout`(50초) 까지 기다린 뒤 `LOCK_WAIT_TIMEOUT` 이 날 것으로 예상 | 실행 안 함 |
| 락 대기 시간 | `SET GLOBAL innodb_lock_wait_timeout = 3` (위와 함께) | 실행 안 함 |
| 중복 확인 SELECT 제거 | `existsByMemberIdAndCouponId` 를 빼면 갭 락이 사라진다. 데드락이 coupons 행의 S→X 에서 날 것으로 예상 | 실행 안 함 |
| READ COMMITTED | `Isolation.READ_COMMITTED` 메서드를 하나 더 두고 비교 | 실행 안 함 |

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

갭 락이 없어도 두 트랜잭션은 coupons 행에 S 락을 쥔 채 커밋 때 X 락이 필요하다. 그래서 겹친 트랜잭션 중 하나만 끝까지 가는 구조는 같을 것으로 예상한다. 아직 검증하지 않았다(3번 표의 "중복 확인 SELECT 제거" 실험).

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

## 5. 보장하는 범위와 해결하지 못한 문제

| 기준 | REPEATABLE READ | SERIALIZABLE |
|---|---|---|
| 사용자당 최대 1장 | 지켜짐. 단 앱의 중복 확인이 아니라 **UNIQUE 제약** 덕분 | 지켜짐 (S 갭 락 + UNIQUE) |
| 발급 건수 ≤ 재고 | **깨짐** (재고 100 에 997장) | 지켜짐 |
| DB 발급 행 = `issued_count` | **깨짐** (갱신 손실) | 지켜짐 |
| 유효 요청이 충분하면 재고만큼 발급 | 초과 발급이라 의미 없음 | **깨짐** (재고 1000 에 103장, 재시도 없음) |
| 먼저 온 요청이 당첨 | 판단 불가 | **보장 안 됨** (12/100) |

해결하지 못한 문제
- SERIALIZABLE 은 정합성을 데드락 롤백으로 지키므로, 동시에 붙는 트랜잭션이 많을수록 실패가 늘어난다. 재시도를 붙이면 성공 건수는 늘겠지만 순서는 더 섞인다(이번 단계에서는 재시도 없이 측정).
- 갭 락 범위는 인덱스에 이미 있는 이웃 레코드에 따라 달라진다. 서로 다른 쿠폰의 요청끼리도 같은 갭을 잠글 수 있다(이번에는 측정하지 않음).
- 같은 회원의 반복 요청이 모두 데드락으로 실패한 경우가 있었다. 특정 요청이 계속 지는지는 2단계 재시도 실험에서 본다.

## 6. 다음 단계로 넘어가는 이유

격리 수준만으로는 "언제, 어떤 행에, 어떤 락을 거는지" 를 고를 수 없다. RR 은 아무 락도 걸지 않아 틀렸고, SERIALIZABLE 은 모든 읽기에 공유 락을 걸어 맞았지만 데드락으로 요청을 버렸다.

2단계에서는 같은 발급 조건에서 락을 직접 고른다.
- 비관적 락: 처음부터 X 락(`FOR UPDATE`)으로 읽어 S→X 경합 자체를 없애면 데드락 대신 줄서기가 되는가?
- 낙관적 락: 락 없이 읽고 커밋 시 버전으로 충돌을 감지하면, 재시도 횟수와 순서는 어떻게 되는가?
