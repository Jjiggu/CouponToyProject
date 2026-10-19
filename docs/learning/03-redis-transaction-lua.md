# 3단계: Redis 발급 자격 예약 — SET 명령만 / MULTI/EXEC / WATCH / Lua

실행 원본 (2026-10-17): [JUnit](results/2026-10-17-stage3-junit.md), [k6](results/2026-10-17-stage3-k6.md), [실제 프로세스 강제 종료·Redis 명령 수](results/2026-10-17-stage3-kill.md)

## 1. 확인할 질문과 예상

질문
- Redis 는 명령을 하나씩 실행한다. 그러면 트랜잭션 없이 SET 명령만 따로 보내도 동시성 문제가 없는가? 여러 클라이언트 소켓을 한 스레드가 번갈아 처리하는 구조(I/O 멀티플렉싱)에서 명령 사이에 다른 요청이 끼어드는가?
- 현재 MULTI/EXEC 구현은 무엇을 보장하고 무엇을 보장하지 않는가? "명령을 묶어 실행" 과 "조건을 검사하고 바꾸기를 한 번에" 는 어떻게 다른가?
- Lua 와 WATCH 는 그 차이를 어떻게 메우는가? 대가는 무엇인가?
- Redis 와 MySQL 이 한 트랜잭션이 아닐 때 어떤 순서에서 둘이 어긋나는가? DB 의 조건부 UPDATE 와 UNIQUE 제약은 그중 무엇을 막는가?

실행 전 예상
- SET 명령만: 명령 하나(SADD)는 원자적이라 같은 회원 중복 예약은 막는다. 하지만 SCARD 로 확인하고 SADD 로 넣는 사이에 다른 요청이 끼어들어 재고보다 많이 예약할 것.
- MULTI/EXEC: SCARD 와 SADD 사이에는 아무것도 끼지 않는다. 하지만 SCARD 결과를 보고 SADD 를 할지 고를 수 없어 일단 넣고 되돌린다. 되돌리기(SREM) 전 틈에서 거짓 품절이나 영구 잔여 예약이 생길 것.
- WATCH: 끼어들면 EXEC 가 무효가 되어 정확하겠지만, 경합이 심하면 재시도가 많을 것.
- Lua: 판단과 예약이 한 번에 실행되어 Redis 안에서는 정확할 것. 그러나 Redis 예약과 DB 확정 사이의 문제는 Lua 로도 해결되지 않을 것.

## 2. 구현과 명령 실행 순서

예약 방식 네 가지([`CouponIssuedRedisRepository`](../../src/main/java/couponToy/CouponToyProject/CouponIssue/repository/CouponIssuedRedisRepository.java))는 모두 "이미 예약됨 → 재고 소진 → 예약" 순서로 판단하고, 원자성만 다르다. 이후 처리는 같다([`RedisIssueCouponService`](../../src/main/java/couponToy/CouponToyProject/CouponIssue/service/RedisIssueCouponService.java)).

```
[0] GET coupon:total:{id}                        총수량 (없으면 DB 에서 읽어 다시 넣음)
[1] Redis 예약                                    방식별 (아래 표)
[2] DB 확정 (IssueConfirmService, 별도 트랜잭션)   INSERT coupon_issues  ← UNIQUE(coupon_id, member_id)
                                                  UPDATE coupons SET issued_count = issued_count + 1
                                                   WHERE coupon_id = ? AND issued_count < total_count   ← 0건이면 SOLD_OUT_AT_DB
[3] [2] 가 실패하면 SREM 으로 예약을 되돌린다. 단 UNIQUE 위반(이미 발급된 회원)은 되돌리지 않는다
```

| 방식 | 명령 | 끼어들 수 있는 곳 |
|---|---|---|
| SET 명령만 | `SISMEMBER` → (Java 확인) → `SCARD` → (Java 확인) → `SADD` | 명령과 명령 사이 어디든 |
| MULTI/EXEC (기존) | `MULTI`, `SCARD`(QUEUED), `SADD`(QUEUED), `EXEC` → Java 가 `[개수, 추가 여부]` 로 판단 → 재고 초과면 `SREM` | EXEC 와 SREM 사이. 이때 SET 에 재고보다 많은 회원이 들어 있다 |
| WATCH | `WATCH` → `SISMEMBER` → `SCARD` → `MULTI`, `SADD`, `EXEC` (감시한 키가 바뀌었으면 EXEC 가 실행되지 않음 → 처음부터 다시, 최대 10회) | 끼어들 수는 있지만 EXEC 가 무효가 된다 |
| Lua | `EVALSHA` 한 번 (스크립트 안에서 `SISMEMBER`, `SCARD`, `SADD`) | 없음. 스크립트가 도는 동안 다른 명령은 실행되지 않는다 |

**명령을 묶어 실행하는 것과 조건 검사·변경을 함께 하는 것은 다르다.** MULTI/EXEC 는 큐에 쌓인 명령들을 다른 클라이언트 명령 없이 연달아 실행해 줄 뿐이다. 큐에 넣는 시점에는 앞 명령의 결과를 알 수 없으므로 "재고가 남았으면 넣는다" 를 표현할 수 없다. 그래서 일단 넣고 Java 에서 보고 되돌린다. Lua 는 앞 명령 결과로 분기할 수 있어서 판단과 변경이 한 실행 안에 들어간다. WATCH 는 MULTI/EXEC 에 "읽은 뒤 바뀌었으면 취소" 조건을 붙인 낙관적 방식이다.

**Redis 의 실행 구조 (이 환경)**: Redis 7.2.13, `io-threads=1`, `multiplexing_api=epoll`. 메인 스레드 하나가 epoll 로 준비된 소켓을 돌며 명령을 하나씩 끝까지 실행한다. 명령 하나는 원자적이지만, 한 클라이언트가 보낸 두 명령 사이에는 다른 소켓의 명령이 실행될 수 있다. `multiplexing_api` 는 설정이 아니라 빌드할 때 OS 에 맞춰 정해진 값이다(Linux 는 epoll, macOS 는 kqueue. 지금은 Docker 의 Linux VM 이라 epoll). `io-threads` 는 기본값 1(메인 스레드만)이다. 늘려도 기본으로는 응답 쓰기만 나누고, 읽기·파싱까지 나누려면 `io-threads-do-reads yes` 를 함께 켜야 한다. 어느 쪽이든 명령 실행은 메인 스레드이고, 연결 수가 적으면 Redis 가 스레드 I/O 를 스스로 끈다는 것이 Redis 설정 파일(redis.conf) 설명이다(켜고 실험하지는 않았다). 앱 쪽 Lettuce 는 기본 설정에서 모든 스레드가 TCP 연결 하나를 같이 쓴다. 그래서 여러 스레드의 명령이 한 연결 위에서도 섞인다.

기존 구현에서 바꾼 것 (`IssueCouponService` → `RedisIssueCouponService`)

| 기존 | 문제 | 바꾼 것 |
|---|---|---|
| Redis 예약이 DB `@Transactional` 메서드 안에 있었다 | DB 가 롤백돼도 Redis 는 그대로다. Redis 왕복 동안 DB 커넥션을 붙잡는다 | 예약은 트랜잭션 밖, 확정만 별도 트랜잭션 |
| 보상(SREM)이 메서드 안 `try` 에만 있었다 | 메서드가 끝난 뒤 커밋이 실패하면 예약만 남는다 | 커밋까지 끝난 `confirm()` 을 `try` 로 감쌌다 |
| DB UNIQUE 위반에도 SREM 했다 | 실제로 발급받은 회원을 Redis 가 잊는다(코드로 확인, 기존 코드로 실행하지는 않음) | UNIQUE 위반이면 예약을 남긴다 (4-6) |
| DB 확정 실패와 Redis 품절이 같은 예외였다 | Redis 와 DB 가 어긋난 것을 구분할 수 없다 | DB 조건부 UPDATE 가 막으면 `SOLD_OUT_AT_DB` |
| 쓰지 않는 쿠폰 SELECT, 메트릭 래퍼 | 흐름이 잘 안 보인다 | 제거 |

실패를 제어하는 장치: [`FailurePoints`](../../src/main/java/couponToy/CouponToyProject/global/failure/FailurePoints.java). 평소에는 아무 일도 하지 않는다. 테스트가 회원 단위로 지점에 동작을 걸어, 그 요청을 멈추거나(실행 순서 고정), 실패시키거나(DB 확정 실패), `Error` 를 던져 프로세스 종료를 흉내 낸다(보상이 실행되지 않음). 앱을 `LAB_HALT_AT=<지점>` 으로 띄우면 그 지점에서 JVM 이 실제로 즉시 종료된다.

실험 API `method`: `REDIS_SET_ONLY`, `REDIS_MULTI_EXEC`, `REDIS_WATCH`, `REDIS_LUA` (기존 `REDIS_TRANSACTIONAL` 은 `REDIS_MULTI_EXEC` 로 바뀌었다). 결과 API 에 `redisOnlyMembers`(Redis 에만 있는 회원), `dbOnlyMembers`(DB 에만 있는 회원)를 추가했다.

## 3. 직접 실행할 명령과 바꿔볼 조건

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
docker compose up -d mysql redis

./gradlew test --tests '*RedisReservationTest'
open build/lab/stage3-redis.md

# 실제 프로세스 강제 종료: 지점에 도달하면 JVM 이 끝난다. 그 뒤 Redis(db 2)·DB 를 보고, LAB_HALT_AT 없이 다시 띄워 재요청한다
LAB_HALT_AT=AFTER_RESERVE SERVER_PORT=18080 \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
SPRING_DATA_REDIS_DATABASE=2 ./gradlew bootRun
docker exec coupon-toy-redis redis-cli -n 2 SMEMBERS coupon:issued:{couponId}
```

HTTP 는 1·2단계와 같은 방법으로 띄우고 `METHOD=REDIS_LUA` 등으로 바꾼다.

| 조건 | 바꾸는 법 | 실행 여부 |
|---|---|---|
| 예약 방식 | 실험 API `method`, k6 `METHOD` | 실행함 |
| 실패 지점과 동작 | `RedisReservationTest` 의 `@CsvSource` 표, `LAB_HALT_AT` | 실행함 |
| WATCH 재시도 상한 | `COUPON_ISSUE_REDIS_WATCH_MAX_ATTEMPTS` (기본 10) | 실행 안 함 |
| Lettuce 커넥션 풀 | `commons-pool2` 의존성 + `spring.data.redis.lettuce.pool.enabled=true`. MULTI/EXEC·WATCH 의 요청당 새 연결이 사라질 것으로 예상 | 실행 안 함 |
| Redis I/O 스레드 | `docker-compose.yml` 에서 Redis `cpus` 를 4 로 올리고 `--io-threads 3 --io-threads-do-reads yes` 로 띄운다(redis.conf 권장: 4코어면 2~3개. `--io-threads` 만 주면 응답 쓰기만 나눈다. 지금 `cpus: "1.0"` 에서는 나눠 쓸 코어가 없다). 명령 실행은 그대로 한 스레드라 정합성 결과는 같을 것으로 예상. 처리량도 거의 같을 것으로 예상: I/O 스레드는 연결 단위로 일을 나누는데 SET 명령만·Lua 는 Lettuce 연결 하나로 나가고, 시간 대부분이 DB 확정이었다(4-7) | 실행 안 함 |
| Redis 영속화 | `--appendonly yes` 등. 재시작 후 예약이 남는지 (6단계) | 실행 안 함 |

## 4. 관찰 결과와 원인

### 4-1. SET 명령만 쓰면 명령 사이에 끼어든다 (질문에 대한 답)

순서 고정 (재고 1, 3회 같은 결과)

| 방식 | 실행 순서 | 결과 |
|---|---|---|
| SET 명령만 | A 가 `SISMEMBER`, `SCARD`(0) 확인 후 멈춤 → B 가 끝까지 실행(발급) → A 의 `SADD` | A 도 예약됨. **A 예약 직후 SCARD 2 (재고 1)**. DB 조건부 UPDATE 가 A 를 막음(`SOLD_OUT_AT_DB`), 보상 SREM |
| WATCH | A 가 `WATCH`, 확인 후 멈춤 → B 가 끝까지 실행 → A 의 `EXEC` | A 의 EXEC 가 무효 → 두 번째 시도에서 다시 읽고 `SOLD_OUT`. SCARD 는 1 을 넘지 않음 |
| MULTI/EXEC, Lua | 확인과 예약 사이에 틈이 없다 | 해당 없음 |

동시 요청에서도 실제로 일어났다
- JUnit(요청 50, 재고 10): SET 명령만은 3번 실행, 회차 9번 모두 Redis 가 재고보다 많이 예약했고 DB 가 막았다. **회차당 5~40건**이 `SOLD_OUT_AT_DB` 였다. 40건이면 거의 모든 요청이 SCARD 를 마친 뒤에 SADD 를 했다는 뜻이다. Lettuce 공유 연결 위에서 50개 스레드의 명령이 같은 걸음으로 나간 것으로 보인다.
- HTTP(요청 1000, 재고 100, 3회): 회차당 0~4건. 요청이 시간상 퍼져 있어 경계에서 겹치는 일이 드물었다.
- 같은 회원 중복은 SET 명령만으로도 한 번도 두 번 예약되지 않았다(JUnit 회원 10명 × 5번에서 매 회차 `DUPLICATE` 40). `SADD` 하나가 "없으면 넣고 결과를 알려 주는" 원자적 명령이기 때문이다.
- MULTI/EXEC, WATCH, Lua 는 모든 실행에서 `SOLD_OUT_AT_DB` 0 건이었다.

정리: Redis 가 명령을 하나씩 실행한다는 것은 "명령 하나" 의 원자성이다. 확인 명령과 변경 명령이 따로 가면, I/O 멀티플렉싱으로 다른 클라이언트의 명령이 그 사이에 실행된다. 앱 쪽 공유 연결도 같은 효과를 낸다.

### 4-2. MULTI/EXEC 의 되돌리기(SREM) 전 틈 (재고 1, 3회 같은 결과)

**다른 회원의 예약 취소와 겹치면 거짓 품절이 생긴다**

순서: A 예약 → B 요청 → A 의 DB 확정이 실패해 예약 취소 → C 요청 → B 마무리. B 는 재고 초과라 SREM 직전에 멈춰 둔다.

| 방식 | B 처리 중 Redis | A 취소 직후 Redis | B | C | 최종 발급 |
|---|---|---|---|---|---:|
| MULTI/EXEC | {A, B} | {B} | 품절 | **품절** | **0** |
| Lua / SET 명령만 / WATCH | {A} | {} | 품절 | 성공 | 1 |

MULTI/EXEC 에서는 B 가 재고 초과로 판정된 뒤에도 SREM 전까지 SET 에 남아 있었다. 그래서 A 가 취소돼 자리가 비었는데도 C 의 EXEC 가 개수 1 을 보고 품절이 됐다. 마지막 요청이 C 였다면 쿠폰은 한 장도 나가지 않는다.

**되돌리기 전에 프로세스가 죽으면 쿠폰 없는 회원이 Redis 에 영구히 남는다**

| 방식 | A 발급 후 B 요청 | 직후 SCARD | B 재요청 |
|---|---|---:|---|
| MULTI/EXEC (SREM 전 종료) | 종료 | **2 (재고 1)** | **`DUPLICATE` (쿠폰이 없는데 이미 발급)** |
| Lua | 품절 | 1 | 품절 |

실제 JVM 강제 종료(`LAB_HALT_AT=MULTI_EXEC_BEFORE_SREM`)로도 같았다. B 요청 중 JVM 이 종료 코드 137 로 끝났고 응답은 오지 않았다. Redis 에는 {A, B}, DB 에는 A 만 남았다. 재시작 후 B 는 409 "이미 쿠폰 발급 받은 사용자입니다" 를 받았다.

### 4-3. Redis 예약 뒤 DB 확정 전 실패와 프로세스 종료 (네 방식 공통, 재고 1, 3회 같은 결과)

| 실패 | 네 방식 모두 | 직후 Redis 에 A | A 재요청 | B 요청 | 최종 |
|---|---|---|---|---|---|
| DB 확정 실패 (예외) | A `ERROR`, 보상 SREM | 없음 | 성공 | 품절 | 발급 1, Redis = DB |
| 예약 직후 프로세스 종료 | A 응답 없음 | **남음** | **`DUPLICATE`** | **품절** | **발급 0**, Redis 에만 A |
| DB 커밋 직전 프로세스 종료 (Lua) | DB 트랜잭션은 롤백 | **남음** | `DUPLICATE` | 품절 | 발급 0 |

실제 JVM 강제 종료(`LAB_HALT_AT=AFTER_RESERVE`, Lua)로도 같았다. Redis {A}, DB 0 이었고, 재시작 후 A 는 "이미 발급", B 는 "품절" 을 받았다. 쿠폰은 한 장도 나가지 않았다.

Lua 는 Redis 안의 판단을 원자적으로 만들 뿐, Redis 예약과 DB 확정을 묶어 주지 않는다. 예약 뒤 프로세스가 죽으면 그 예약이 왜 남았는지 판단할 근거가 Redis 와 DB 어디에도 없다.

### 4-4. 예약 취소 전 같은 회원의 재요청 (Lua, 재고 1, 3회 같은 결과)

A 의 DB 확정이 실패한 뒤 SREM 직전에 멈춰 두었다. 멈춘 동안 A 는 다시 요청하면 `DUPLICATE`(쿠폰이 없는데 이미 발급), B 는 `SOLD_OUT` 을 받았다. SREM 이 끝난 뒤 A 의 재요청은 성공했다. 보상이 끝나기 전에는 Redis 가 "아직 확정되지 않은 예약" 과 "발급 완료" 를 구분하지 못한다.

### 4-5. 동시 요청 (JUnit, 요청 50, 스레드 50, Hikari 10, 1회차 실행 3회)

| 시나리오 | SET 명령만 | MULTI/EXEC | WATCH | Lua | 비관적 락 |
|---|---|---|---|---|---|
| 재고 10, 회원 50 | 10 발급, **DB 가 막은 과다 예약 5~9** (실행 3번 합쳐 5~40). 서버 p95 99~115ms | 10 발급. p95 109~145ms | 10 발급, 시도 최대 7, 충돌 170~262. p95 117~357ms | 10 발급. **p95 21~38ms** | 10 발급. p95 183~213ms |
| 재고 50, 회원 50 | 50. p95 148~216ms | 50. p95 140~209ms | **15~21** (29~35건이 10회 소진). p95 231~401ms | 50. p95 138~242ms | 50. p95 234~329ms |
| 회원 10 × 5번 | 10, 중복 40. p95 21~29ms | 10. p95 41~48ms | 10, 충돌 157~231. p95 118~147ms | 10. p95 16~22ms | 10, UNIQUE 8~15. p95 119~156ms |

- 모든 방식에서 DB 기준 정합성(1인 1장, 재고 이하, 행 = `issued_count`)이 지켜졌고, 모든 요청이 끝난 뒤 Redis 예약 회원과 DB 발급 회원이 같았다(보상 SREM 포함).
- Redis 에서 거절된 요청은 DB 에 가지 않는다. 재고 10 시나리오에서 Lua 의 락 대기는 9건(발급 10건만 DB 에 감), 비관적 락은 49건이었다.

### 4-6. Redis 가 발급 기록을 잃은 뒤 재요청 (Lua, 재고 10, 3회 같은 결과)

A 발급 후 Redis 키를 지웠다(데이터 유실 흉내). A 가 다시 요청하자 Redis 는 통과시켰고 DB UNIQUE 가 막았다(`UNIQUE_VIOLATION`). 고친 코드는 이때 예약을 지우지 않으므로, 그 뒤 Redis 에 A 가 다시 기록됐고 한 번 더 요청하자 Redis 에서 `DUPLICATE` 로 막혔다. 기존 코드는 여기서 SREM 했으므로 같은 요청이 올 때마다 DB 까지 갔을 것이다(코드로 확인).

### 4-7. HTTP (k6, 회원 1000, VU 100, 요청 1000, 1·2단계와 같은 조건, 3회 범위)

| 실험 | 방식 | 발급 | Redis 통과 후 DB 가 막음 | 먼저 온 N명 중 당첨 | HTTP p50 / p95 ms | 접수 / 발급 완료 req/s |
|---|---|---|---:|---:|---|---|
| 재고 100 | SET 명령만 | 100 | **0~4** | 100/100 | 10~12 / 334~630 | 1205~2174 / 120~217 |
| 재고 100 | MULTI/EXEC | 100 | 0 | 100/100 | 13~23 / 571~829 | 952~1493 / 95~149 |
| 재고 100 | WATCH | 100 | 0 | 17~18/100 | 34~442 / 635~1151 | 214~472 / 21~47 |
| 재고 100 | Lua | 100 | 0 | 100/100 | 2~33 / **213~291** | **1667~3125** / 167~312 |
| 재고 100 | 비관적 락 | 100 | - | 100/100 | 112~210 / 308~343 | 431~704 / 43~70 |
| 재고 1000 | SET 명령만 | 1000 | 0 | 1000/1000 | 235~264 / 291~377 | 369~431 |
| 재고 1000 | MULTI/EXEC | 1000 | 0 | 1000/1000 | 255~316 / 389~607 | 288~380 |
| 재고 1000 | WATCH | **107~460** | 0 | - | 291~433 / 852~1349 | 169~267 / 35~123 |
| 재고 1000 | Lua | 1000 | 0 | 1000/1000 | 204~284 / 243~366 | 355~472 |
| 재고 1000 | 비관적 락 | 1000 | - | 1000/1000 | 297~350 / 378~684 | 247~324 |
| 회원 100 × 10번 | SET 명령만 | 100 | 0 | 100/100 | 3 / 226~247 | 2857~2941 / 286~294 |
| 회원 100 × 10번 | MULTI/EXEC | 100 | 0 | 100/100 | 16~29 / 706~853 | 781~1266 / 78~127 |
| 회원 100 × 10번 | WATCH | 100 | 0 | 100/100 | 64~73 / 606~856 | 362~448 / 36~45 |
| 회원 100 × 10번 | Lua | 100 | 0 | 100/100 | 3~9 / 225~420 | 1075~2941 / 108~294 |
| 회원 100 × 10번 | 비관적 락 | 100 | - | 100/100 | 114~252 / 241~525 | 350~741 / 35~74 |

- 재고 1000(전원 발급)에서는 모든 요청이 DB 확정을 거치므로 시간 대부분이 DB 쿠폰 행 UPDATE 줄서기였다. Lua·SET 명령만 355~472 req/s, 비관적 락 247~324 req/s 로 차이는 크지 않았다.
- 차이가 큰 곳은 거절이 많은 경우다. 재고 100 에 요청 1000 이면 Redis 방식은 900건을 DB 에 보내지 않고 돌려보낸다. 비관적 락은 1000건 모두 쿠폰 행 X 락을 기다린 뒤 거절했다.
- MULTI/EXEC 와 WATCH 는 Lettuce 기본 설정에서 **요청(시도)마다 새 Redis 연결을 맺었다.** 1회차 실행 기준 MULTI/EXEC 는 요청 1200건(워밍업 포함)에 연결 약 1200개였다. WATCH 는 재고 1000 에서 측정 요청의 시도 7688번과 워밍업을 합쳐 연결 7889개였다. SET 명령만과 Lua 는 0개였다. 순차 20건 측정에서도 같았다. MULTI/EXEC 의 지연에는 Redis 실행이 아니라 연결을 맺는 비용이 섞여 있다.
- 실행 간 편차가 컸다(같은 조건 p95 가 2배 가까이 차이). 이 표로 방식 간 미세한 순위를 매기지 않는다.

선착순 관찰
- Redis 방식에서 당첨자는 DB 가 아니라 Redis 가 예약 명령을 실행한 순서로 정해진다. 그 순서는 진입점 도착 순서와 다를 수 있다.
- JUnit(50 스레드 동시 출발)에서 먼저 온 10명 중 당첨은 Lua 7~8, MULTI/EXEC 5~9, SET 명령만 6~9 명이었다. 동시에 출발한 스레드들이 Redis 에 도착하는 순서는 진입 번호와 거의 무관했다.
- HTTP 에서는 Lua·MULTI/EXEC·SET 명령만 모두 3회 100/100 이었다. 요청이 시간상 퍼져 있으면 두 순서가 거의 같아진다.
- WATCH 는 충돌한 요청이 다시 줄을 서므로 17~18/100 이었다.

## 5. 보장하는 범위와 해결하지 못한 문제

DB 쪽 방어선이 막는 것과 못 막는 것
- 조건부 UPDATE(`issued_count < total_count`): Redis 가 재고보다 많이 예약해도(SET 명령만, 5~40건) DB 발급은 재고를 넘지 않았다.
- UNIQUE(coupon_id, member_id): Redis 가 발급 기록을 잃어도 같은 회원의 두 번째 행은 들어가지 않았다.
- 못 막는 것: 새어 나간 예약(쿠폰 없는 회원의 `DUPLICATE`, 아무도 못 받는 재고), 거짓 품절, 진입 순서와 다른 당첨. 이때도 DB 기준 검사는 모두 통과했다(4-2, 4-3 의 결과 API `checks` 는 모두 true, `redisOnlyMembers` 1). **DB 가 오류를 막았다고 Redis 구현이 맞는 것은 아니다.**

| 기준 | SET 명령만 | MULTI/EXEC | WATCH | Lua |
|---|---|---|---|---|
| Redis 예약 ≤ 재고 (요청 처리 중) | **깨짐** (SCARD 2/1, 동시 5~40건) | 깨짐(SREM 전 일시 초과). 발급 자격은 정확 | 지켜짐 | 지켜짐 |
| 같은 회원 중복 예약 방지 | 지켜짐 (SADD 하나) | 지켜짐 | 지켜짐 | 지켜짐 |
| 취소와 겹칠 때 거짓 품절 없음 | 지켜짐 | **깨짐** (최종 0장) | 지켜짐 | 지켜짐 |
| Redis 안 판단 중 프로세스 종료 시 잔여 예약 없음 | 해당 없음 (마지막 SADD 만 쓴다) | **깨짐** (SREM 전 종료) | 해당 없음 (EXEC 만 쓴다) | 해당 없음 (한 번에 실행) |
| 예약 후 DB 확정 전 종료 시 잔여 예약 없음 | **깨짐** | **깨짐** | **깨짐** | **깨짐** |
| 경합이 심해도 재고만큼 발급 | 지켜짐 (DB 가 걸러냄) | 지켜짐 | **깨짐** (재고 1000 에 107~460) | 지켜짐 |
| 먼저 온 요청이 당첨 | 보장 안 됨 | 보장 안 됨 | 보장 안 됨 | 보장 안 됨 (Redis 실행 순서) |

해결하지 못한 문제
- Redis 와 MySQL 은 하나의 트랜잭션이 아니다. 예약과 확정 사이에 프로세스가 죽으면 예약만 남고, 그 예약을 되돌릴 근거가 어디에도 기록되지 않는다.
- 보상 SREM 은 "DB 확정 실패" 를 전제로 한다. 커밋은 됐는데 응답만 잃은 경우에도 SREM 하므로, 그때는 Redis 가 실제 발급자를 잊는다(재현하지 않았다).
- 보상이 끝나기 전에는 Redis 가 "확정 전 예약" 과 "발급 완료" 를 구분하지 못한다(4-4).
- 사용자 응답은 여전히 DB 확정이 끝날 때까지 기다린다. 재고 1000 처럼 전원이 발급되는 경우에는 처리량이 DB 쿠폰 행 UPDATE 줄서기에 묶였다.

## 6. 다음 단계로 넘어가는 이유

Lua 로 "Redis 안에서의 판단" 은 정확해졌다. 4~6단계의 공통 기준으로 Lua 예약(`reserveWithLua`)과 같은 DB 확정(`IssueConfirmService`)을 쓴다.

남은 문제는 둘 사이다. 응답이 DB 확정까지 기다려야 하고, 예약과 확정 사이에 프로세스가 죽으면 예약이 새어 나간다. 4단계부터는 이 둘 사이에서 작업을 전달·실행·복구하는 방식을 바꾼다.
- 4단계 `@Async`: 예약 후 바로 202 로 응답하고 DB 확정을 같은 프로세스의 스레드 풀로 넘긴다. 접수와 완료가 갈라진다. 큐가 넘치거나 프로세스가 죽으면 작업은 어디서 사라지는가?
- 5·6단계: 작업을 프로세스 밖(Redis Pub/Sub, Streams)으로 옮기면 유실과 재처리는 어떻게 달라지는가?
