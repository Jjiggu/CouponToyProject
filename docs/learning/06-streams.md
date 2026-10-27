# 6단계: Redis Streams — 메시지를 남기고, 커밋 뒤에 ACK 하기

실행 원본 (2026-10-19): [JUnit](results/2026-10-19-stage6-junit.md), [HTTP](results/2026-10-19-stage6-http.md), [Redis 재시작](results/2026-10-19-stage6-redis-restart.md)

## 1. 확인할 질문과 예상

질문
- 5단계 Pub/Sub 은 받을 쪽이 없거나 받고 죽으면 메시지가 사라졌다. Stream 에 남기면 그 메시지는 어디에 어떤 상태로 남고, 누가 다시 처리하는가?
- 아직 전달되지 않은 메시지(대기)와, 전달됐지만 ACK 되지 않은 메시지(pending)는 어떻게 다른가?
- **DB 커밋 뒤 ACK 전에 소비자가 죽으면** 같은 메시지를 다시 처리한다. 다시 처리해도 결과가 같은가? DB UNIQUE 예외만 잡으면 `issued_count` 까지 맞는가?
- 계속 실패하는 메시지는 언제까지 다시 시도하고 어디에 남기는가?
- 발행자가 Redis 지급 처리 뒤 XADD 전에 죽으면? 이를 막는 가장 작은 개선은?
- Redis 가 재시작되면 스트림, 그룹, pending 은 남는가? 지금 설정 기준으로.

실행 전 예상
- 소비자가 없거나 죽어도 메시지는 스트림에 남는다. 읽고 ACK 하지 않은 메시지는 그 소비자의 pending 에 남아, 다른 소비자가 XAUTOCLAIM 으로 가져갈 수 있다.
- 같은 그룹 안에서는 한 메시지를 한 소비자에게만 준다. 소비자를 늘리면 나눠 처리한다(5단계처럼 중복 처리하지 않는다).
- 커밋 뒤 ACK 전 종료면 같은 메시지를 두 번 처리한다. 두 번째는 UNIQUE 위반이 되고, INSERT 와 UPDATE 가 한 트랜잭션이라 `issued_count` 는 늘지 않을 것이다. 전달은 적어도 한 번이고, 결과가 한 번만 반영되는 것은 처리 쪽이 같은 일을 두 번 해도 같은 결과를 내기 때문이다.
- 지급 처리 뒤 XADD 전 종료는 3단계 4-3 과 같이 Redis 지급 처리만 남긴다. 지급 처리와 XADD 가 둘 다 Redis 명령이므로 Lua 하나로 묶으면 그 틈이 없어질 것이다.
- 지금 Redis 는 AOF·RDB 를 끈 설정이라 재시작하면 스트림도 사라질 것이다.

## 2. 구현과 명령 실행 순서

4~6단계 공통 조건대로 3단계 Lua 지급 처리와 같은 실제 쿠폰 지급(`IssueConfirmService`)을 쓴다. 바꾼 공통 로직 두 가지는 아래에 이유와 함께 적었다.

```
발행자 프로세스 (local, 18080)                   StreamIssueCouponService   ← 요청 스레드
  REDIS_STREAM         [1] Lua 지급 처리 (3단계와 같음)  [2] XADD coupon:issue:stream * memberId .. couponId ..
                           두 명령 사이에서 죽으면 Redis 지급 처리만 남는다. XADD 가 실패하면 SREM
  REDIS_STREAM_ATOMIC  [1] EVALSHA 한 번: 지급 가능 여부 판단 → XADD → SADD (최소 개선)
  → HTTP 202 (QUEUED)

Redis: 메시지를 스트림에 남긴다. 그룹 coupon-issuers 는 "어디까지 전달했는지" 와 소비자별 pending(전달했지만 ACK 안 됨)을 기록한다

소비자 프로세스 (stream-consumer, 18081~)       StreamIssueConsumer
  같은 그룹에 소비자 4개 (consumer-{pid}-1~4), 소비자마다 스레드 하나가
  [3] XREADGROUP GROUP coupon-issuers consumer-… COUNT 10 BLOCK 500 STREAMS coupon:issue:stream >
  [4] IssueConfirmService.confirm()  — INSERT, 조건부 UPDATE, COMMIT (3단계와 같음)
  [5] 결과에 따라
        성공                      → XACK
        UNIQUE 위반(이미 지급)     → XACK (커밋 뒤 ACK 전에 죽었다가 다시 처리한 경우도 여기)
        품절·없는 회원·없는 쿠폰   → SREM, XACK (다시 해도 같은 거절)
        그 밖의 실패(일시적일 수 있음) → ACK 하지 않음, Redis 지급 처리도 남김 → pending 에 남는다
  회수: 1초마다 XAUTOCLAIM … 5000ms(최소 유휴) 0-0 COUNT 100 으로 오래된 pending 을 가져와 [4][5] 를 다시 한다
        XPENDING 으로 본 전달 횟수가 3 을 넘으면 coupon:issue:stream:dead 에 따로 남기고, SREM, XACK
```

- **소비자를 늘려 동시 처리**: 4·5단계는 작업 스레드 풀(스레드 4, 큐 50000)에 넘겼다. 여기서는 같은 그룹에 소비자 4개를 두고 소비자가 직접 처리한다. 읽은 메시지를 스레드 풀에 넘기면 ACK 전의 메시지가 다시 메모리 큐에 쌓이고, 언제 ACK 할지가 꼬인다. 한 소비자가 한 번에 읽는 양은 10건(COUNT)이고, 읽은 메시지는 처리 전이라도 Redis 의 pending 에 있다.
- **공통 로직 변경 1: 보상(SREM) 시점.** 3~5단계는 실제 쿠폰 지급이 실패하면 바로 Redis 지급 처리를 취소했다. Stream 은 실패한 메시지를 다시 처리하므로, 일시적 실패에 바로 되돌리면 재처리가 성공했을 때 Redis 가 실제 지급받은 회원을 잊는다(5단계 4-5 와 같은 어긋남). 그래서 다시 해도 같은 결과인 거절과, 전달 횟수 상한을 넘겨 따로 보관할 때만 되돌린다. 실제 쿠폰 지급 코드(`IssueConfirmService`)는 그대로다.
- **공통 로직 변경 2: 지급 처리와 XADD 를 한 스크립트로(`REDIS_STREAM_ATOMIC`).** 3단계 Lua 지급 처리 스크립트에 XADD 를 넣은 것이다. 판단 순서와 결과는 같다. Lua 는 실행 중 오류가 나도 앞선 명령을 되돌리지 않으므로 실패할 수 있는 XADD 를 SADD 보다 먼저 둔다. 비교를 위해 두 단계 방식(`REDIS_STREAM`)도 남겼다.
- **소비자 이름은 프로세스마다 다르다**(`consumer-{pid}-n`). 다시 띄운 프로세스는 새 이름이라 죽은 프로세스의 pending 을 `>` 로는 받지 못하고, 회수(XAUTOCLAIM)로 가져온다. 죽은 소비자의 이름은 그룹에 남는다(지우는 코드는 없다).
- **스트림 키는 Redis DB 번호별이다.** 5단계 Pub/Sub 채널과 달리 db 1(테스트)과 db 2(실험 앱)의 스트림이 따로다.
- **ACK 해도 메시지는 스트림에서 지워지지 않는다.** ACK 는 pending 에서 빼는 것뿐이다. 이 구현은 스트림을 자르지 않으므로(XTRIM·MAXLEN 없음) 길이가 계속 늘어난다. 자르는 정책을 두면 처리 전 메시지까지 잘릴 수 있다(4-9).
- 실패 지점: `STREAM_BEFORE_CONFIRM`(읽은 뒤 실제 지급 전), `STREAM_BEFORE_ACK`(커밋 뒤 ACK 전). 지급 처리 뒤 XADD 전은 기존 `AFTER_RESERVE`.
- Spring Data Redis 3.4 에는 XAUTOCLAIM 이 없어 Lettuce 명령을 직접 썼다. 읽기는 `StreamMessageListenerContainer` 의 `receive`(자동 ACK 아님)다. `receiveAutoAck` 는 읽을 때 NOACK 을 붙여 pending 에 남기지 않으므로 쓰지 않았다.

## 3. 직접 실행할 명령과 바꿔볼 조건

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
docker compose up -d mysql redis

./gradlew test --tests '*StreamIssueTest'
open build/lab/stage6-stream.md

# HTTP: 발행자와 소비자를 따로 띄운다 (5단계와 같은 방법, 소비자 프로필만 다름)
./gradlew bootJar
COMMON="SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true SPRING_DATA_REDIS_DATABASE=2 SPRING_JPA_SHOW_SQL=false SPRING_JPA_PROPERTIES_HIBERNATE_SHOW_SQL=false"
env $COMMON SPRING_PROFILES_ACTIVE=local SERVER_PORT=18080 java -jar build/libs/CouponToyProject-0.0.1-SNAPSHOT.jar            # 발행자
env $COMMON SPRING_PROFILES_ACTIVE=stream-consumer SERVER_PORT=18081 java -jar build/libs/CouponToyProject-0.0.1-SNAPSHOT.jar  # 소비자

BASE_URL=http://localhost:18080 METHOD=REDIS_STREAM SCENARIO=burst TOTAL_COUNT=1000 \
MEMBERS=1000 VUS=100 ITERATIONS=1000 WARMUP=200 k6 run performance/k6/coupon-issue.js

# 스트림 상태: 길이, 그룹(전달한 위치·pending 수), 소비자별 pending, 따로 보관한 메시지
docker exec coupon-toy-redis redis-cli -n 2 XLEN coupon:issue:stream
docker exec coupon-toy-redis redis-cli -n 2 XINFO GROUPS coupon:issue:stream
docker exec coupon-toy-redis redis-cli -n 2 XPENDING coupon:issue:stream coupon-issuers
docker exec coupon-toy-redis redis-cli -n 2 XRANGE coupon:issue:stream:dead - +

# 실제 프로세스 종료 지점: 발행자 지급 처리 뒤 XADD 전 / 소비자 커밋 뒤 ACK 전
LAB_HALT_AT=AFTER_RESERVE ... (발행자)        LAB_HALT_AT=STREAM_BEFORE_ACK ... (소비자)
```

k6 는 Stream 방식일 때 결과 API 의 `redisOnlyMembers` 가 0 이 되거나 10초 동안 줄지 않을 때까지 기다린다(회수가 5초 뒤에 돌기 때문에 5단계의 2초보다 길게 두었다).

| 조건 | 바꾸는 법 | 실행 여부 |
|---|---|---|
| 그룹 소비자 수 | `COUPON_ISSUE_STREAM_CONSUMERS` (기본 4), 소비자 프로세스 개수 | 프로세스 1·2개 실행함 (4-10). 프로세스당 소비자 수는 바꾸지 않음 |
| 회수 최소 유휴 시간 | `COUPON_ISSUE_STREAM_CLAIM_MIN_IDLE_MS` (기본 5000) | 바꾸지 않음. JUnit 은 0 으로 직접 회수 |
| 전달 횟수 상한 | `COUPON_ISSUE_STREAM_MAX_DELIVERIES` (기본 3) | 기본값으로 실행함 (4-5) |
| 지급 처리와 XADD | `REDIS_STREAM`(두 단계) / `REDIS_STREAM_ATOMIC`(한 스크립트) | 실행함 (4-7, 4-10, 4-12) |
| 소비자 프로세스 종료 | `kill -KILL`, `kill -TERM` | 실행함 (4-11) |
| 스트림 자르기 | `XTRIM MAXLEN` | JUnit 으로 실행함 (4-9) |
| Redis 영속화 | 별도 Redis 컨테이너, AOF·RDB 끔 / AOF everysec | 실행함 (4-13) |

## 4. 관찰 결과와 원인

### 4-1. 소비자가 없을 때 (JUnit 재고 1, HTTP 재고 1000)

| 순서 | 스트림 [길이, 대기, pending] | DB 지급 |
|---|---|---:|
| 소비자 0개에서 A 요청 → `QUEUED`(202) | [1, 1, 0] | 0 |
| 소비자를 붙인 뒤 | [1, 0, 0] (처리 결과 SUCCESS 1) | 1 |

HTTP 에서도 소비자 없이 1000명이 요청하자 1000건이 대기로 남았고, 소비자 프로세스를 띄우자 앱 시작을 포함해 8.2초 만에 모두 실제 지급됐다. 5단계(받은 구독자 0, 메시지 사라짐)와 달리 메시지가 스트림에 남는다. 처리 뒤에도 길이가 1 인 것은 ACK 가 메시지를 지우지 않기 때문이다(4-9).

### 4-2. 대기와 pending (JUnit, 재고 2, 소비자 1)

소비자가 A 를 읽고 실제 지급 직전에 멈춘 사이 B 를 넣었다. 스트림은 [길이 2, 대기 1, pending 1] 이었다. A 는 전달됐지만 ACK 되지 않아 소비자의 pending 에 있고, B 는 아직 그룹의 누구에게도 전달되지 않았다(소비자 하나가 A 에 묶여 있으므로). 놓아주자 둘 다 처리되어 [2, 0, 0] 이 됐다.

대기 메시지는 소비자가 `>` 로 읽으면 가져간다. pending 메시지는 그 소비자가 ACK 하거나, 다른 소비자가 XAUTOCLAIM 으로 가져가야 끝난다. 소비자가 죽으면 대기는 다른 소비자가 그냥 읽지만, 그 소비자의 pending 은 회수가 있어야 한다.

### 4-3. DB 커밋 뒤 ACK 전 종료 (JUnit, HTTP 실제 JVM 종료)

| 순서 | 결과 |
|---|---|
| 소비자 c1 이 실제 지급을 커밋하고 ACK 전에 종료 | DB 지급 1, 스트림 pending 1 |
| 다른 소비자가 XAUTOCLAIM 으로 가져와 다시 처리 | 실제 지급이 **UNIQUE 위반**(이미 지급) → ACK. 처리 결과 SUCCESS 1, ALREADY_ISSUED 1 |
| 최종 | DB 지급 행 1, **`issued_count` 1**, Redis {A}, pending 0 |

실제 JVM 종료(`LAB_HALT_AT=STREAM_BEFORE_ACK`)로도 같았다. 소비자가 커밋 뒤 종료되자 DB 지급 1, pending 1 이었고, 새 소비자 프로세스가 앱 시작을 포함해 5.5초 뒤에 회수해 "이미 지급" 으로 ACK 했다. `issued_count` 는 1 이었다.

**UNIQUE 예외만 잡는 것이 충분한가.** 이 구현에서는 충분했다. 실제 지급은 한 트랜잭션에서 INSERT(UNIQUE)를 먼저 하고 조건부 UPDATE 를 뒤에 한다. 두 번째 처리는 INSERT 에서 막혀 트랜잭션 전체가 롤백되므로 `issued_count` 가 다시 늘지 않는다. 다음 경우라면 충분하지 않다(코드로 따진 것이고 재현하지 않았다).
- INSERT 와 UPDATE 가 다른 트랜잭션이거나, UPDATE 를 먼저 커밋하는 경우: 두 번째 처리에서 UPDATE 만 반영될 수 있다.
- 실제 지급 뒤에 DB 밖의 일(알림 보내기, 다른 시스템 호출)을 하는 경우: UNIQUE 가 그 일을 막아 주지 않는다.
- UNIQUE 위반에 보상(SREM)을 하는 경우: 다시 처리할 때마다 실제 지급받은 회원을 Redis 가 잊는다(3단계에서 고친 것).

전달은 적어도 한 번이고, 결과가 한 번만 반영되는 것은 실제 지급을 두 번 해도 결과가 같기 때문이다.

### 4-4. 일시적 DB 실패 (JUnit, 재고 1)

첫 처리의 실제 쿠폰 지급이 실패하자 ACK 하지 않았고 Redis 지급 처리도 남겼다(스트림 pending 1, Redis 지급 처리 있음). 회수 뒤 다시 처리해 성공했다(처리 결과 RETRY 1, SUCCESS 1). 3~5단계라면 실패하자마자 Redis 지급 처리를 취소했고, 사용자가 다시 요청해야 했다.

### 4-5. 계속 실패하면 (JUnit, 재고 1, 전달 횟수 상한 3)

실제 쿠폰 지급이 매번 실패하게 했다. 첫 처리와 회수 두 번까지 3번 시도했고, 세 번째 회수에서 XPENDING 의 전달 횟수가 4 가 되어 처리하지 않고 `coupon:issue:stream:dead` 에 남겼다. 그때 Redis 지급 처리를 취소하고 ACK 했다. 스트림은 [1, 0, 0, 따로 보관 1] 이 됐다. 실패를 멈춘 뒤 A 가 다시 요청하자 실제 지급됐다.

따로 보관한 메시지를 다시 처리하는 절차는 만들지 않았다. 사람이 보고 판단해야 하는 목록이다.

### 4-6. 같은 그룹의 소비자 2개 (JUnit 재고 20, HTTP 재고 1000)

JUnit 에서 같은 그룹에 소비자 2개를 두고 20건을 넣자 처리 결과는 SUCCESS 20 이었다. 5단계 구독자 2개(모든 메시지를 두 번 처리)와 달리 그룹 안에서는 한 메시지를 한 소비자에게만 준다. HTTP 에서 소비자 프로세스 2개(그룹 소비자 8개)로 1000건을 3번 보냈을 때도 재처리 로그(이미 지급)는 0건이었다.

### 4-7. 발행자가 Redis 지급 처리 뒤 XADD 전에 죽으면 (JUnit, HTTP 실제 JVM 종료)

| 방식 | 결과 |
|---|---|
| `REDIS_STREAM` (지급 처리 → XADD 두 단계), 지급 처리 직후 종료 | 스트림 길이 0, Redis 에 A 지급 처리. A 재요청 **`DUPLICATE`**. 쿠폰은 없다 |
| `REDIS_STREAM_ATOMIC` (한 스크립트) | Redis 지급 처리와 메시지가 함께 생겼고 실제 지급됐다. 이 경로에는 둘 사이에 멈출 지점이 없다 |

실제 JVM 종료(`LAB_HALT_AT=AFTER_RESERVE`)도 같았다. 두 단계 방식은 응답 없이 발행자가 종료됐고, Redis 에 지급 처리만 있고 스트림에 메시지가 없었다. 재시작 뒤 같은 회원은 409 를 받았다. 같은 설정으로 한 스크립트 방식을 보내자 202 를 받고 실제 지급됐다.

Stream 이 메시지를 남겨도, 메시지를 남기기 전의 틈은 Stream 이 막아 주지 않는다. 지급 처리와 XADD 가 둘 다 같은 Redis 의 명령이라서 Lua 스크립트 하나로 묶을 수 있었다. 실제 쿠폰 지급과 ACK 는 다른 시스템이라 이렇게 묶을 수 없고, 그래서 4-3 의 멱등성에 기댄다.

### 4-8. 한 스크립트 안에서 XADD 가 실패하면 (JUnit, 재고 1)

스트림 키를 문자열 값으로 막아 XADD 가 실패하게 했다. `REDIS_STREAM_ATOMIC` 은 `ERROR` 였고 Redis 지급 처리가 남지 않았다. Lua 스크립트는 실행 중 오류가 나도 앞서 실행한 명령을 되돌리지 않는다. 그래서 스크립트에서 XADD 를 SADD 보다 먼저 두었다(SADD 를 먼저 두면 Redis 지급 처리만 남는다. 이 순서는 재현하지 않았다). 두 단계 방식은 XADD 예외를 잡아 Redis 지급 처리를 취소했다.

### 4-9. 보존: ACK 해도 남고, 자르면 사라진다 (JUnit, HTTP)

- ACK 는 pending 에서 빼는 것뿐이다. JUnit 동시 요청에서 스트림 길이가 회차마다 10 → 20 → 30, 50 → 100 → 150 으로 늘었고, HTTP 실험이 끝났을 때 db 2 의 스트림 길이는 18902 였다. 이 구현은 자르지 않으므로 메모리를 계속 쓴다.
- 처리 전에 자르면 사라진다. 소비자 없이 A, B 를 넣고 `XTRIM MAXLEN 1` 로 자르자 A 의 메시지가 지워졌다. 소비자를 붙이자 B 만 실제 지급됐고 A 는 Redis 지급 처리만 남았다. 길이만 보고 자르는 정책은 처리하지 않은 메시지도 지운다.

### 4-10. 동시 요청 (JUnit 3번 실행 × 3회차, HTTP 3회)

JUnit (요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 시나리오 | 접수 결과 | 소비자 처리 결과 | 지급 성공 응답 뒤 처리 끝까지 ms | 먼저 온 N명 중 당첨 |
|---|---|---|---|---|
| 재고 10, 회원 50 | QUEUED 10, SOLD_OUT 40 | SUCCESS 10 | 45~59 | 7~10 / 10 |
| 재고 50, 회원 50 | QUEUED 50 | SUCCESS 50 | 157~261 | 50 / 50 |
| 회원 10 × 5번 | QUEUED 10, DUPLICATE 40 | SUCCESS 10 | 33~98 | 10 / 10 |

HTTP (발행자 18080, 소비자 프로세스 18081, 같은 세션에서 4단계 비동기와 비교)

| 실험 | 방식 | HTTP p50 / p95 ms | 지급 성공 응답 req/s | 실제 지급 완료 req/s | 실제 지급까지 s | 마지막 응답 시점 미완료 |
|---|---|---|---|---|---|---|
| 재고 100 | Stream (두 단계) | 11~35 / 24~95 | 2242~7194 | 139~255 | 0.39~0.72 | 54~84 / 100 |
| 재고 1000 | Stream (두 단계) | 9~24 / 13~42 | 3876~10309 | 339~517 | 1.93~2.95 | 969~996 / 1000 |
| 재고 1000 | Stream (한 스크립트) | 10~17 / 17~26 | 5405~8475 | 418~485 | 2.06~2.40 | 975~992 / 1000 |
| 재고 1000 | 비동기 (4단계) | 11 / 19~24 | 7752~7936 | 417~523 | 1.91~2.40 | 967~986 / 1000 |
| 재고 1000 | Stream, 소비자 프로세스 2개 | 11~14 / 18~27 | 6173~8264 | 346~445 | 2.25~2.89 | 972~985 / 1000 |

- 모든 회차에서 DB 기준 정합성이 지켜졌고, 처리가 끝난 뒤 Redis 에 지급 처리된 회원과 DB 지급 회원이 같았다. 정상 실행에서 재시도·재처리·따로 보관 로그는 0건이었다.
- 실제 지급 완료 처리량은 같은 세션의 4단계 비동기와 범위가 겹쳤다. 소비자 프로세스를 2개로 늘려도 늘지 않았다. 실제 지급이 같은 쿠폰 행 하나를 갱신하므로 소비자를 늘려도 그 행 앞의 줄만 길어진다(4단계 4-7, 4-9).
- 두 단계와 한 스크립트의 응답 시간·처리량은 범위가 겹쳤다. Redis 왕복이 한 번 줄지만 이 측정에서 드러날 차이는 아니었다.

### 4-11. 소비자 프로세스 종료 (HTTP, 재고 1000, 3회씩)

[원본](results/2026-10-19-stage6-http.md). 발행이 300건을 넘으면 소비자 JVM 에 신호를 보냈고, 발행이 끝난 뒤 새 소비자 프로세스(새 이름)를 띄웠다.

| 신호 | 종료 직후 DB 지급 | 종료 직후 스트림 대기 / pending | 새 소비자 시작부터 모두 처리까지 (앱 시작 포함) | 최종 | 새 소비자의 재처리(이미 지급) |
|---|---|---|---|---|---|
| `kill -KILL` | 44~71 | 916~926 / 15~31 | 7.65~12.47s | 1000 = `issued_count`, Redis 에만 0, DB 에만 0 | 1~2건 |
| `kill -TERM` | 30~60 | 916~956 / 17~29 | 7.71~7.94s | 같음 | 3~4건 |

- 4·5단계(865~967건 유실)와 달리 한 건도 사라지지 않았다. 대기 메시지는 새 소비자가 바로 읽었고, 죽은 소비자의 pending(소비자가 읽어 두었던 최대 10건 × 4)은 최소 유휴 5초가 지난 뒤 회수했다.
- 재처리 1~4건은 죽기 직전에 커밋했지만 ACK 하지 못한 메시지다. 모두 UNIQUE 위반으로 끝났고 `issued_count` 는 1000 이었다.
- SIGTERM 도 0.13~0.19초 만에 끝났고, 커밋 뒤 ACK 전 메시지와 pending 이 남았다. 정상 종료가 처리 중인 메시지를 마무리해 준다고 기대할 수 없지만, 남은 것은 회수로 처리된다.

### 4-12. 실제 프로세스 종료 지점 (HTTP, 요청 1건)

4-3, 4-7 에 적었다. 원본은 [HTTP 원본](results/2026-10-19-stage6-http.md) 의 (5).

### 4-13. Redis 재시작 (별도 컨테이너, redis-cli)

[원본](results/2026-10-19-stage6-redis-restart.md). Redis 지급 처리 3건과 메시지 3건을 넣고, 소비자가 2건 읽어 1건 ACK 한 상태(대기 1, pending 1)에서 재시작했다.

| 설정 | 정상 종료(SIGTERM) 뒤 | 강제 종료(SIGKILL) 뒤 |
|---|---|---|
| 지금 프로젝트 설정 `--appendonly no --save ""` | **스트림·그룹·pending·지급 처리 모두 없음** | 같음 |
| AOF `appendfsync everysec` | 스트림 3, 그룹, pending 1, 지급 처리 3 그대로 | 같음 |

- 지금 설정에서는 Redis 가 재시작되면 실제 지급을 기다리던 메시지와 Redis 지급 처리가 함께 사라진다. 지급 처리까지 사라지므로 Redis 와 DB 가 어긋나지는 않지만(둘 다 없음), 202 를 받은 사용자의 요청은 흔적 없이 사라지고 DB 에 이미 지급된 회원은 Redis 가 잊는다(3단계 4-6 과 같은 상태).
- AOF everysec 로 띄우면 프로세스가 죽어도 남았다. OS·머신 장애 때 마지막 1초 쓰기를 잃을 수 있는 것은 3단계 4-8 과 같다(재현하지 않음).
- AOF 로 다시 읽은 뒤 그룹의 `entries-read` 와 `lag` 이 비어 있었다(nil). 이 구현은 대기 수를 `XRANGE` 로 직접 세므로 영향이 없었다.

### 4-14. 소비자 프로세스의 Redis 연결

메시지가 없는 상태에서도 소비자 프로세스(소비자 4개, 읽기 대기 0.5초)가 10초에 새 Redis 연결 73개를 맺었다. 블로킹 `XREADGROUP` 은 공유 연결이 아니라 전용 연결을 쓰는데(Spring Data Redis `LettuceStreamCommands`), 커넥션 풀이 없으면 읽을 때마다 새로 맺고 끊는다. 3단계 4-8 의 MULTI/EXEC·WATCH 와 같은 원인이다. 그래서 호스트 TIME_WAIT 가 180~350 에 머물렀고, HTTP 실험 스크립트의 대기 기준을 올려야 했다. 커넥션 풀을 켠 측정은 하지 않았다.

## 5. 보장하는 범위와 해결하지 못한 문제

| 기준 | @Async (4단계) | Pub/Sub (5단계) | Streams (6단계) |
|---|---|---|---|
| 1인 1장, 재고 이하, 행 = `issued_count` | 지켜짐 | 지켜짐 | 지켜짐 (재처리 포함) |
| 응답이 곧 실제 지급 완료 | 아니오 | 아니오 | 아니오 |
| 처리할 쪽이 없을 때 | 해당 없음 | 사라짐 | **남았다가 나중에 처리** |
| 처리할 쪽이 받은 뒤 강제 종료 | 큐의 작업 사라짐 (865~897건) | 사라짐 (952~967건) | **pending 에 남아 회수, 유실 0** |
| 커밋 뒤 확인 전 종료 | 결과 기록만 잃음 | 해당 없음 | 다시 처리 → UNIQUE 위반 → ACK. `issued_count` 그대로 |
| 일시적 실제 지급 실패 | 바로 되돌림 (재요청 필요) | 바로 되돌림 | 재시도, 상한(3) 넘으면 따로 보관하고 되돌림 |
| 같은 실제 지급을 두 번 실행 | 없음 | 구독자 수만큼 매번 | 재처리 때만(커밋 뒤 ACK 전 종료, 회수가 처리 중인 메시지를 가져간 경우). 그룹 안 소비자끼리는 없음 |
| 발행자가 지급 처리 뒤 넘기기 전 종료 | Redis 지급 처리만 남음 | Redis 지급 처리만 남음 (같은 구조, 재현 안 함) | 두 단계 방식은 Redis 지급 처리만 남음. **한 스크립트면 틈 없음** |
| Redis 재시작 | 해당 없음 (큐는 JVM) | 해당 없음 (저장 안 함) | 지금 설정은 메시지·지급 처리 모두 사라짐. AOF everysec 면 남음 |
| 사용자에게 결과 전달 | 없음 | 없음 | 없음 |

Streams 로 "받은 뒤 사라지는" 문제는 막았다. 메시지가 Redis 에 남고, ACK 를 DB 커밋 뒤로 미루고, 죽은 소비자의 메시지를 회수하기 때문이다. 대가로 같은 메시지가 다시 처리될 수 있고, 다시 처리해도 결과가 같도록 실제 지급이 멱등이어야 한다. 전달은 적어도 한 번이다.

해결하지 못한 문제
- **Redis 영속화**: 지금 설정(AOF·RDB 끔)에서는 Redis 재시작으로 대기 중인 메시지와 Redis 지급 처리가 모두 사라진다. AOF everysec 도 OS·머신 장애의 마지막 1초는 보장하지 않는다.
- **스트림 보존**: ACK 한 메시지가 계속 쌓인다. 길이 기준으로 자르면 처리 전 메시지도 지워진다. 처리가 끝난 위치(모든 그룹이 ACK 한 ID)를 기준으로 자르는 장치가 필요하지만 만들지 않았다.
- **회수의 최소 유휴 시간(5초)**: 이보다 오래 걸리는 정상 처리는 다른 소비자가 가져가 두 번 처리할 수 있다. 결과는 멱등성으로 같지만 쓰기를 두 번 시도한다. 반대로 짧게 잡을수록 중복이 늘고, 길게 잡을수록 죽은 소비자의 메시지가 늦게 처리된다.
- **죽은 소비자 이름**이 그룹에 남는다. 프로세스를 띄울 때마다 새 이름을 쓰므로 쌓인다.
- **따로 보관한 메시지**를 다시 처리하는 절차가 없다.
- **커넥션**: 커넥션 풀이 없어 소비자가 읽을 때마다 새 Redis 연결을 맺는다(4-14).
- **사용자 응답**: 202 를 받은 사용자는 여전히 결과를 모른다. 결과를 조회하거나 알리는 경로가 필요하다.

## 6. 단계를 마치며

1~6단계에서 같은 질문("한 사람에게 한 장, 준비한 수량까지만")을 서로 다른 지점에서 지켰다.

| 단계 | 지킨 곳 | 남긴 문제 |
|---|---|---|
| 1 격리 수준 | 같은 행을 읽고 쓰는 트랜잭션 | READ COMMITTED·REPEATABLE READ 는 초과 지급과 갱신 손실, SERIALIZABLE 은 데드락 롤백으로 재고만큼 지급되지 않음. 격리 수준만으로는 "읽고 판단하고 쓰기" 가 지켜지지 않았다 |
| 2 비관적·낙관적 락 | 쿠폰 행 락 / 버전 검사 | 같은 행 앞의 줄, 충돌·재시도. 락 부여 순서는 도착 순서가 아니다(CATS) |
| 3 Redis 지급 처리 | Lua 로 판단과 지급 처리를 한 번에, DB 는 조건부 UPDATE·UNIQUE 로 마지막 방어 | Redis 와 MySQL 은 한 트랜잭션이 아니다. 지급 처리 뒤 실제 지급 전에 죽으면 Redis 지급 처리만 남는다 |
| 4 @Async | 응답과 실제 지급을 나눔 | 실제 지급 작업이 JVM 메모리에만 있어 죽으면 사라진다 |
| 5 Pub/Sub | 실제 지급을 다른 프로세스로 | 메시지를 저장하지 않아 받을 쪽이 없거나 받고 죽으면 사라진다. 중복 전달 |
| 6 Streams | 메시지를 남기고, 커밋 뒤 ACK, 회수, 멱등 실제 지급 | Redis 영속화와 스트림 보존, 사용자에게 결과 전달 |

어느 한 장치도 혼자서는 "정확히 한 번 지급" 을 만들지 못했다. 이 프로젝트에서 정합성을 지킨 것은 끝까지 DB 의 조건부 UPDATE 와 UNIQUE 였다. 그 위에 올린 장치들(Redis 지급 처리, 비동기 전달)은 DB 로 가는 요청을 줄이거나 응답을 빠르게 하는 대신, 실패했을 때 무엇이 어디에 남는지를 바꿨다. 각 단계 문서의 "보장하는 범위" 표는 그 남는 위치를 정리한 것이다.
