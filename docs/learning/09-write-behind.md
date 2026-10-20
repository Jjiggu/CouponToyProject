# 9단계: write-behind (Redis 를 기준으로, DB 는 나중에 채운다)

실행 원본: [JUnit](results/2026-10-20-stage9-junit.md), [장애 실험](results/2026-10-20-stage9-loss.md), [EC2 측정](results/2026-10-21-stage9-ec2.md)

## 1. 질문과 예상

8단계에서 쿠폰 한 장의 확정 상한은 약 145 TPS 였다. 모든 확정이 쿠폰 행 하나(`issued_count`)의 락을 쥔 채 커밋 동기화를 기다렸기 때문이다. 순간적으로 사람이 몰리는 이벤트에서 흔히 쓰는 방법이 write-behind 다.

> Redis 를 1차 기준 데이터(SSOT)로 쓰고, 요청을 로그(원문은 Kafka)로 넘긴 뒤 DB 적재는 컨슈머가 한다. 누락분은 나중에 종합 처리해 결국 일치(eventual consistency)를 맞춘다. 로그를 먼저 쓰고 DB 에 반영하는 DB 의 WAL 에서 나온 생각이다.

질문
- 쿠폰 행을 갱신하지 않으면 확정(DB 적재) 속도가 얼마나 오르는가?
- 그 대가로 무엇을 잃는가? DB 가 하던 초과 발급 방어가 사라지면, Redis 가 기록을 잃을 때 무슨 일이 생기는가?
- "나중에 맞춘다" 는 어디까지 맞출 수 있는가?

예상: 처리량은 오른다. 대신 정확성이 Redis 의 영속 설정에 달린다. WAL 이 안전한 것은 로그를 디스크에 쓴 뒤에 응답하기 때문인데, 지금 Redis 는 영속화를 꺼 두었다.

## 2. 우리 구조에 옮긴 것

| 원문 | 9단계 | 이유 |
|---|---|---|
| Redis 가 1차 기준 | Redis 의 `coupon:issued:{id}` 가 발급 회원 명단의 기준. 판단은 3단계 Lua(중복 → 재고 → 예약) | 3단계에서 검증한 로직 |
| Kafka 로 요청을 넘김 | **Redis Stream** `coupon:wb:stream` (6단계와 다른 키) | 이 프로젝트는 새 메시지 브로커를 추가하지 않는다. 같은 Lua 안에서 기록(XADD)과 예약(SADD)을 함께 하므로 "예약은 됐는데 로그가 빠진" 틈이 없다 |
| DB 적재는 컨슈머 | 적재기가 최대 100건을 한 트랜잭션에 **INSERT 만** 하고, 커밋 뒤 ACK. 쿠폰 행은 갱신하지 않는다 | 8단계의 한계(쿠폰 행 하나에 줄 서기)를 없앤다 |
| 누락분은 나중에 종합 처리 | 대사(`POST /lab/write-behind/coupons/{id}/reconcile`): Redis 에만 있는 회원을 넣고, `issued_count` 를 행 수로 한 번에 맞춘다. DB 에만 있는 회원과 재고 초과는 알려만 준다 | |

실행 순서

```
요청 스레드 (local 프로필)   WriteBehindIssueService
  SELECT 회원 존재 확인 (기본 키 조회. 적재기는 확인 없이 INSERT 하므로 여기서 막는다)
  EVALSHA: SISMEMBER → SCARD ≥ 재고 → XADD coupon:wb:stream → SADD coupon:issued:{id}   → 202 (QUEUED)

적재기 (wb-loader 프로필, 스레드 1개, 소비자 이름 loader)   WriteBehindLoader.loadOnce()
  XREADGROUP 0  (자기 pending: 읽고 ACK 못 한 것) → 없으면 XREADGROUP >  (새 메시지) COUNT 100
  BEGIN; INSERT INTO coupon_issues ... VALUES (..),(..),... ON DUPLICATE KEY UPDATE member_id = member_id; COMMIT
  XACK
  (읽을 것이 없으면 20ms 쉰다. 실패하면 ACK 하지 않고 1초 뒤 다시)

대사   WriteBehindLoader.reconcile()
  SELECT member_id FROM coupon_issues (DB 먼저) → SMEMBERS coupon:issued:{id}
  Redis 에만 있는 회원 INSERT, UPDATE coupons SET issued_count = (SELECT COUNT(*) ...)
```

- 같은 메시지를 다시 처리하거나 대사와 겹쳐도 이미 있는 (쿠폰, 회원) 행은 바뀌지 않는다(UNIQUE)
- 대사는 DB 를 먼저 읽는다. Lua 가 SADD 와 XADD 를 한 번에 하므로 DB 에 들어온 회원은 그 전에 Redis 에 있었다. 그래서 요청이 계속 들어오는 중에도 "DB 에만 있는 회원" 은 Redis 가 기록을 잃은 경우뿐이다
- 기존 코드는 바꾸지 않았다. 9단계 코드는 `WriteBehind` 패키지에 있고, 실험 API 에 연결하려고 방식 이름(`REDIS_WRITE_BEHIND`), `LabService` 분기, 202 응답 목록에 한 줄씩 등록했다

## 3. 실행 명령과 바꿔 볼 조건

```bash
./gradlew test --tests "*.WriteBehindIssueTest"

# HTTP: 발행자와 적재기 프로세스
SPRING_PROFILES_ACTIVE=local     SERVER_PORT=18080 ... java -jar build/libs/CouponToyProject-0.0.1-SNAPSHOT.jar
SPRING_PROFILES_ACTIVE=wb-loader SERVER_PORT=18081 ... java -jar build/libs/CouponToyProject-0.0.1-SNAPSHOT.jar
BASE_URL=http://localhost:18080 METHOD=REDIS_WRITE_BEHIND k6 run performance/k6/coupon-issue.js
curl -X POST localhost:18080/lab/write-behind/coupons/{id}/reconcile
```

바꿔 볼 조건: 적재 묶음 크기(`coupon.issue.write-behind.batch-size`, 기본 100), Redis 영속 설정(`--appendonly`, `--appendfsync`), 죽이는 대상(Redis, 적재기).

## 4. 관찰 결과

**정상 (JUnit, 재고 10, 회원 20명이 두 번씩 요청 40)**
- 접수 직후 DB 발급 행 0, Redis 에만 있는 회원 10. 요청 결과 접수 10, 품절 20, 중복 10
- 적재기 한 번에 10건을 한 트랜잭션으로 넣었다. 이때 `issued_count` 는 0 그대로라 "issued_count = 발급 행 수" 검증이 거짓이었다
- 대사 뒤 `issued_count` 10, 검증 4개 모두 참

**커밋 뒤 ACK 전 종료 (JUnit)**: pending 3건을 다시 적재해도 행은 3개 그대로였다.

**적재 요청 기록만 잃은 경우 (JUnit)**: 스트림을 비우자 적재기는 0건. 대사가 Redis 명단으로 5건을 채웠다.

**판매 중 장애 (Mac, 재고 1000, 회원 3000명이 한 번씩 초당 200건 15초, 예약 300명쯤에서 죽임)**

| 죽인 것 | 접수(202) | DB 발급 행 | 결과 |
|---|---:|---:|---|
| Redis, 영속화 끔 | 1411 | 1408 | 재시작 뒤 명단이 0 이 되어 1000장을 다시 팔았다. **408장 초과 발급.** 접수했지만 DB 에 끝내 없는 3명 |
| Redis, AOF everysec | 1000 | 1000 | 잃은 것 없음 |
| 적재기 | 1000 | 1000 | 잃은 것 없음. 새 적재기가 같은 이름으로 pending 을 다시 읽었다 |

- 영속화를 끈 경우: DB 는 수량을 검사하지 않으므로 초과 발급을 막지 못했다. 대사는 "DB 에만 있는 회원 408명, 재고 초과" 를 알려 줄 뿐이다. 접수됐지만 아직 적재되지 않았던 3명은 Redis 명단과 함께 사라져 대사로도 되살릴 수 없다(접수 1411 − 발급 행 1408)
- AOF everysec 에서 잃은 것이 없는 것은 Redis **프로세스**만 죽였기 때문이다. Redis 는 응답하기 전에 AOF 에 쓰기(write)를 넘기므로, 프로세스가 죽어도 OS 에 넘어간 내용은 남는다. `everysec` 의 "최대 1초 유실" 은 OS 가 죽거나 전원이 나갈 때 이야기이고, 재현하지 않았다
- 세 경우 모두 5xx 는 0건이었다. Redis 가 1초 안쪽에 다시 떴고 그동안 명령이 기다렸다가 처리된 것으로 본다(재연결 중 요청이 어디서 기다렸는지는 확인하지 않음)

**확정(DB 적재) 속도 (EC2, 8단계와 같은 인스턴스·설정·판정, 2회)**

| | 8단계: 쿠폰 행 갱신 (동기 Lua·비동기·Pub/Sub·Stream) | 9단계: write-behind |
|---|---:|---:|
| 따라간 최대 속도 | 140 | **1900** |
| 확정 상한 | 144~147 | 1967~1983 (2000 req/s) |
| 한계를 넘었을 때 MySQL 컨테이너 CPU | 24~25% | **100%** |
| 무엇이 찼나 | 쿠폰 행의 락 (커밋 동기화를 기다리며 쥠) | MySQL CPU (1 CPU 제한) |

| 목표 req/s | 1600 | 1700 | 1800 | 1900 | 2000 |
|---|---:|---:|---:|---:|---:|
| 적재 TPS (두 회차) | 1600~1601 | 1698~1700 | 1797~1799 | 1900~1901 | 1967~1983 |
| 응답 p95 | 4ms | 4ms | 4~5ms | 8ms | 126~241ms |
| MySQL CPU | 86~87% | 91% | 96% | 99% | 100% |

- 쿠폰 행 갱신을 빼자 같은 하드웨어에서 따라간 최대 속도가 140 → 1900 으로 약 13배가 됐다. 적재기는 여러 건을 한 번에 커밋하므로(800 req/s 에서 초당 커밋 930 번 중 회원 확인 몫 800 번을 빼면 적재기 커밋은 약 130 번, 한 번에 평균 6건 정도로 어림) 커밋 동기화를 기다리는 횟수가 줄고, 같은 행의 락을 기다리지도 않는다
- 2000 에서는 MySQL CPU 가 찼다. 그러자 **접수(202) 응답도 느려졌다**(p95 2~8ms → 126~241ms, k6 가 못 보낸 요청 196~631건). 요청마다 하는 회원 확인 SELECT 가 MySQL 을 기다리기 때문이다
- 탐색(1회씩, 200~2400)에서도 1800 까지 조건을 충족했고, 2000·2200·2400 은 MySQL CPU 100% 에서 적재가 1963~2101 TPS 에 머물렀다(원본)

**다음 한계는 적재가 아니라 요청마다 하는 회원 확인이었다.** 쿼리별 MySQL CPU(`performance_schema`, 1600 req/s 한 번):

| 문장 | 1회당 CPU | 요청 하나당 |
|---|---:|---:|
| `SELECT COUNT(*) FROM members WHERE member_id = ?` (회원 확인) | 115µs | 1번 |
| `SET autocommit = ?` | 42µs | 2번 |
| `COMMIT` | 57µs | 1번 |
| `SET SESSION TRANSACTION READ ONLY` / `READ WRITE` | 46µs / 38µs | 1번씩 |
| 적재기 여러 행 INSERT | 행당 약 30µs | - |

- 요청 하나에 MySQL CPU 약 340µs. 그중 SELECT 는 115µs 이고 나머지 약 225µs 는 트랜잭션 설정 문장이다. Spring Data 의 `existsById` 가 읽기 전용 트랜잭션에서 돌아 `SET autocommit=0 → READ ONLY → SELECT → COMMIT → READ WRITE → SET autocommit=1` 이 함께 오간다
- 1 / (340 + 30)µs ≈ 2700 이 계산상 상한인데 실제로는 약 2000 에서 막혔다. 차이는 나누어 재지 않았다

## 5. 보장 범위와 해결하지 못한 문제

보장하는 것 (Redis 가 기록을 잃지 않는 동안)
- 쿠폰별 회원당 한 번, 재고 이하: Lua 가 판단한다
- 접수한 요청은 결국 DB 에 들어간다: 기록(XADD)과 예약(SADD)이 한 번에 남고, 적재기는 커밋 뒤에만 ACK 하며, 기록이 사라져도 대사가 Redis 명단으로 채운다
- 같은 요청을 두 번 적재해도 행은 하나다(UNIQUE)

보장하지 않는 것
- **Redis 가 기록을 잃으면 정확성이 깨진다.** 영속화를 끄면 재시작만으로 초과 발급이 생겼다. DB 는 막지 못하고 대사는 알려만 준다. 3~8단계는 DB 의 조건부 UPDATE 가 마지막 방어선이었는데, 9단계는 그것을 빼서 속도를 얻는 구조다
- 접수(202)는 DB 적재 완료가 아니다. 적재되기 전 Redis 가 기록을 잃으면 접수한 요청이 사라진다
- `issued_count` 는 대사 전까지 실제와 다르다. 화면이나 다른 기능이 이 값을 읽으면 틀린 값을 본다
- OS 장애나 전원이 나갔을 때 AOF everysec 가 잃는 범위(최대 1초)는 재현하지 않았다. 잃지 않으려면 `appendfsync always` 가 필요하고, 그러면 Redis 도 쓰기마다 디스크 동기화를 기다린다
- 적재기는 하나라고 가정했다(소비자 이름 고정). 여럿 띄우려면 이름을 나누고 죽은 쪽 pending 을 가져와야 한다(6단계 XAUTOCLAIM)

## 6. 다음에 볼 것

- 회원 확인의 비용 줄이기: 트랜잭션 없이 SELECT 한 번으로 하기, 드라이버 설정(`useLocalSessionState`, `readOnlyPropagatesToServer`)으로 중복 문장 줄이기, 회원 확인을 DB 밖으로 빼기. 각각 MySQL CPU 와 한계가 얼마나 바뀌는지
- Redis `appendfsync always` 일 때 접수 속도. 내구성을 Redis 쪽에서 다시 사면 비용이 어디로 가는지
