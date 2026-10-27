# 10단계: 회원 확인에서 쓸데없는 트랜잭션 설정 문장 없애기

실행 원본: [JUnit](results/2026-10-23-stage10-junit.md), [EC2 측정](results/2026-10-23-stage10-ec2.md)

## 1. 질문과 예상

9단계 write-behind 는 2000 req/s 근처에서 MySQL CPU 가 찼다. 쿼리별 CPU 를 보니 요청마다 하는 회원 확인이 요청당 MySQL CPU 약 340µs 를 썼고, 그중 실제 SELECT 는 115µs, 나머지 약 225µs 는 트랜잭션 설정 문장이었다([09 4절](09-write-behind.md)).

```
SET autocommit=0 → SET SESSION TRANSACTION READ ONLY → SELECT COUNT(*) ... → COMMIT → SET SESSION TRANSACTION READ WRITE → SET autocommit=1
```

카카오페이 기술 블로그의 [JPA Transactional 글](https://tech.kakaopay.com/post/jpa-transactional-bri/) "실제로 set_option과 commit이 성능에 영향을 미칠까?" 와 같은 문제다. Spring Data JPA 의 기본 메서드(`existsById`, `findById` 등)는 `SimpleJpaRepository` 클래스에 붙은 `@Transactional(readOnly = true)` 때문에 단건 조회도 트랜잭션 안에서 돌고, MySQL 드라이버(Connector/J)가 그 시작과 끝을 SET 문장과 COMMIT 으로 보낸다.

질문
- 회원 확인을 트랜잭션 없이 하면 설정 문장이 실제로 사라지는가?
- 같은 부하에서 MySQL CPU 가 얼마나 줄고, 따라간 최대 속도가 얼마나 오르는가?
- 그다음 한계는 무엇인가?

예상: 요청당 MySQL CPU 가 340µs → SELECT 만큼(약 115µs)으로 줄어 한계가 2~3배 오른다. 그다음은 다시 MySQL CPU(남은 SELECT 와 적재 INSERT)나 적재기 하나가 한계가 된다.

## 2. 바꾼 것과 실행 순서

9단계에서 회원 확인 하나만 바꿨다. Lua, 스트림 `coupon:wb:stream`, 적재기, 대사는 9단계 것을 그대로 쓴다. 9단계 코드는 수정하지 않았다(새 패키지 `WriteBehindNoTx`, 방식 이름 `REDIS_WRITE_BEHIND_NO_TX`).

```java
// MemberExistsRepository: JpaRepository 가 아니라 Repository 를 상속(기본 메서드의 클래스 단위 트랜잭션을 물려받지 않게)
@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
@Query("select count(m) from Member m where m.memberId = :memberId")
long countByMemberId(@Param("memberId") Long memberId);
```

- `SUPPORTS`: 부르는 쪽에 트랜잭션이 있으면 참여하고, 없으면 트랜잭션 없이 실행한다. `LabService.issue()` 에는 트랜잭션이 없고 OSIV 도 꺼져 있어(`open-in-view: false`) 자동 커밋 연결에서 SELECT 하나만 나간다
- 글과 같이 `NOT_SUPPORTED`(바깥 트랜잭션을 멈추고 연결을 하나 더 쓸 수 있음), `NEVER`(바깥 트랜잭션이 있으면 예외)는 쓰지 않았다
- SQL 모양은 9단계 `existsById` 와 같은 COUNT 다. 두 단계의 차이는 트랜잭션 설정 문장뿐이다

```
9단계  SET autocommit=0 → SET SESSION TRANSACTION READ ONLY → SELECT COUNT(*) → COMMIT → SET SESSION TRANSACTION READ WRITE → SET autocommit=1
10단계 SELECT COUNT(m1_0.member_id)
그 뒤는 같다: EVALSHA(중복 → 재고 → XADD → SADD) → 202, 적재기가 묶어서 INSERT
```

## 3. 실행 명령과 바꿔 볼 조건

```bash
./gradlew test --tests "*.WriteBehindNoTxIssueTest"
CONFIGS=wb  ROUNDS=2 WARM_RATE=200 RATES="1600 1800 1900 2000"           performance/ec2/ec2.sh tps s10-wb
CONFIGS=wb10 ROUNDS=2 WARM_RATE=200 RATES="1600 4600 4800 5000 5200 5400" performance/ec2/ec2.sh tps s10-wb10
```

인스턴스를 중지했다 켜면 물리 서버가 바뀔 수 있다. 9단계 기준값은 10월 21일 측정(따라간 최대 속도 1900)이 아니라, 10단계와 같은 날 같은 인스턴스에서 다시 잰 값(1800)을 쓴다.

## 4. 관찰 결과

**설정 문장이 사라졌다 (JUnit, 회원 확인 200번에 늘어난 MySQL 문장 카운터)**

| 경로 | SET 문장 (`Com_set_option`) | COMMIT | SELECT |
|---|---:|---:|---:|
| 9단계 `existsById` | 800 (요청당 4) | 200 | 200 |
| 10단계 `countByMemberId` | 0 | 0 | 200 |

`Com_set_option` 은 `SET autocommit` 2번과 `SET SESSION TRANSACTION READ ONLY` / `READ WRITE` 2번을 함께 센다.

**쿼리별 MySQL CPU (EC2, 1600 req/s 1회, 예열·측정 준비 포함 누적)**

| 문장 | 9단계 (10/21) 호출 / 1회당 | 10단계 (10/23) 호출 / 1회당 |
|---|---|---|
| 회원 확인 SELECT | 54,386 / 115µs | 54,313 / 140µs |
| `SET autocommit` | 123,804 / 42µs | 13,380 / 56µs |
| `SET SESSION TRANSACTION READ ONLY` / `WRITE` | 54,405 / 46µs, 54,406 / 38µs | 상위 목록에 없음 |
| `COMMIT` | 61,896 / 57µs | 6,680 / 195µs |

- 요청에 붙던 설정 문장 다섯 개가 사라지고 SELECT 하나만 남았다. 요청당 MySQL CPU 는 약 340µs → 약 140µs
- 10단계에 남은 `SET autocommit`·`COMMIT` 은 적재기 트랜잭션과 측정 준비(회원 생성)의 것이다(COMMIT 1번당 SET autocommit 2번 꼴)
- SELECT 1회당 CPU 가 115 → 140µs 로 늘었다. 두 측정은 날짜와 인스턴스가 달라 원인을 가르지 못했다

**같은 날, 같은 부하, 같은 인스턴스 (EC2, 2회)**

| | 9단계 | 10단계 |
|---|---:|---:|
| 1600 req/s 의 MySQL CPU | 89~95% | **37%** |
| 1600 req/s 의 MySQL 커밋/초 | 1701 | **109** |
| 1600 req/s 의 응답 p95 | 6~9ms | **2ms** |
| 따라간 최대 속도 | 1800 | **4800** (약 2.7배) |
| 한계를 넘었을 때 적재 TPS | 1863~1873 | 5000~5191 (5000·5200), 5400 에서 무너짐 |

10단계 한계 근처 (두 회차)

| 목표 req/s | 적재 TPS | 응답 p95 | k6 가 못 보낸 요청 | 끝났을 때 미완료 | MySQL CPU | 판정 |
|---|---|---|---|---|---|---|
| 4800 | 4796~4797 | 8~10ms | 0 | 27~96 | 91% | 충족 |
| 5000 | 4992~5000 | 11~16ms | 1, 29 | 77~639 | 95~96% | 미달(못 보냄) |
| 5200 | 5188~5191 | 15~17ms | 0, 34 | 63~149 | 99% | 1회차만 충족 |
| 5400 | 3450~5028 | 29~545ms | 312, 27,923 | 10,136~29,370 | 87~100% | 미달 |

- 5000·5200 은 적재가 목표를 따라갔지만 k6 가 요청 1~34건을 제때 보내지 못해 조건("못 보낸 요청 0")에 걸렸다. 한계 바로 아래라 판정이 한두 건으로 갈리는 구간이다
- 5400 에서는 무너졌다. 미완료가 1~3만 건 쌓이고 적재 TPS 가 떨어졌다. 탐색에서도 5500·6000·6500 에서 같은 모습이었다(6000: 응답 p95 64ms 로 빠른데 적재는 3974 TPS, 미완료 53,572)

**다음 한계: MySQL CPU 를 요청 쪽 SELECT 와 적재기가 나눠 쓴다**
- 5000 req/s 에서 회원 확인 SELECT 만으로 0.14ms × 5000 ≈ 0.7 코어다. 나머지를 적재 INSERT 와 커밋이 쓰고, MySQL CPU 가 95% 를 넘는다
- 그러자 적재기 커밋이 4800~5000 req/s 의 초당 73~80 번에서 5400 req/s 의 34~52 번으로 줄었다(10단계에서는 회원 확인에 COMMIT 이 없으므로 MySQL 커밋은 거의 적재기 것이다). 묶음 최대 100건이므로 적재기 하나가 따라갈 수 있는 속도도 그만큼 떨어지고, 넘친 요청은 Redis 스트림에 쌓였다
- 요청이 많을수록 적재가 더 느려지는 구간이다. 지급 성공 응답(202)은 빠르게 계속되므로 응답 시간만 보면 알 수 없다. 9단계 5절에서 적은 write-behind 의 위험(DB 반영이 뒤처짐)이 여기서 실제로 드러났다
- 적재기 하나(묶음 100)와 MySQL CPU 중 무엇이 먼저 한계인지는 이 측정으로 가르지 못했다. 적재 묶음이 느려진 것이 CPU 가 찬 MySQL 때문일 수 있다

## 5. 보장 범위와 해결하지 못한 문제

- 정합성 보장은 9단계와 같다. 바꾼 것은 회원 확인 쿼리의 트랜잭션뿐이다. 10단계 측정 23번(탐색 10, 쿼리별 CPU 1, 본 측정 12. 준비 단계에서 끝난 7000 제외) 모두 정합성 기준(회원당 한 번, 재고 이하, Redis 지급 처리 = DB 행)이 지켜졌다
- **SUPPORTS 는 부르는 쪽에 트랜잭션이 없을 때만 효과가 있다.** 나중에 `LabService.issue()` 나 그 위에 `@Transactional` 이 붙거나 OSIV 를 켜면, 회원 확인이 그 트랜잭션에 참여해 설정 문장이 다시 나간다. 코드로 막아 두지 않았다(테스트의 문장 카운터 비교가 다시 실패해서 알 수 있다)
- 트랜잭션 없이 실행하므로 `readOnly = true` 의 효과(Hibernate 의 읽기 전용 최적화 등)는 없다. 이 COUNT 쿼리에는 필요 없다
- MySQL CPU 가 차면 적재가 뒤처진다(4절). 막을 장치(접수 속도 제한, 적재 지연 감시)는 없다
- 결과 API 가 지급 10만 건을 넘으면 느려져(명단 비교에 List.contains), 표의 "확정까지" 시간이 실제보다 길게 잡혔다. 기존 코드라 고치지 않았다
- 인스턴스를 다시 켜면 같은 9단계도 따라간 최대 속도가 1900 → 1800 으로 달랐다. 날이 다른 측정끼리는 비교하지 않는다

## 6. 다음에 볼 것

- 회원 확인 자체를 MySQL 밖으로: 남은 SELECT(요청당 약 140µs)가 이제 요청 쪽 MySQL CPU 의 전부다. Redis 에 회원 명단을 두거나, 회원 확인을 로그인 단계에서 끝내고 지급 요청에서는 빼는 방법이 있다. 각각 무엇을 믿고 무엇을 잃는지가 다르다
- 적재기를 여러 개로, 또는 묶음을 크게: 적재가 뒤처질 때 따라갈 수 있는지. 이름을 나누고 죽은 쪽 pending 을 가져오는 일(6단계 XAUTOCLAIM)이 필요해진다
- 접수 속도 제한: 적재가 뒤처지기 시작하면(스트림에 쌓인 양) 접수를 늦추거나 거절하기
