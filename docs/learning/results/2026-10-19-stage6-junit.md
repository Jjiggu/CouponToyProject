# 6단계 JUnit 실행 원본 (2026-10-19): Redis Streams

`./gradlew test --tests '*StreamIssueTest'` 가 `build/lab/stage6-stream.md` 에 남긴 내용을 옮겼다.

실행 조건
- Redis 7.2.13 (Docker, AOF·RDB 끔), 테스트는 db 1. 스트림 `coupon:issue:stream`, 그룹 `coupon-issuers`, 따로 보관 `coupon:issue:stream:dead`. 테스트마다 두 스트림을 지우고 시작한다
- MySQL 8.0.36 (Docker, 1 CPU / 1GB), REPEATABLE READ, DB `CouponToyProject_test`. 테스트 JVM: OpenJDK 17.0.19, Hikari 최대 10, Lettuce 기본 설정
- 소비자는 테스트가 `StreamMessageListenerContainer` 를 만들어 `receive`(자동 ACK 아님)로 띄운다. pollTimeout 100ms, 한 번에 10건. 동시 요청은 같은 그룹 소비자 4개
- 회수(XAUTOCLAIM)는 앱에서는 1초마다 최소 유휴 5초로 돌지만, 테스트는 필요한 시점에 최소 유휴 0 으로 직접 부른다. 전달 횟수 상한 3
- 처리 완료는 소비자 처리 결과 수(`StreamIssueConsumer.results`)가 기대한 만큼 쌓일 때까지 10ms 간격으로 기다렸다
- 스트림 상태 `StreamState[length, waiting, pending, deadLetters]` = 스트림 길이, 그룹에 아직 전달되지 않은 메시지(대기), 전달됐지만 ACK 되지 않은 메시지(pending), 따로 보관한 메시지
- 6단계 테스트를 3번 실행했고 순서 고정 결과는 3번 모두 같았다(회원 번호만 다름). 그 뒤 전체 테스트(149개, 그중 2개는 MySQL 전역 설정 실험이라 기본 실행에서 건너뜀)를 1번 실행했다. 아래는 1회차 기록이고, 동시 요청 표는 2·3회차도 붙였다
- 동시 요청 표의 스트림 길이가 회차마다 늘어나는 것은 ACK 해도 메시지가 스트림에서 지워지지 않기 때문이다

---

## 계속 실패: 전달 횟수 상한(3) 뒤 따로 보관 (재고 1)

- 첫 처리와 회수 3번 뒤 스트림 상태: [StreamState[length=1, waiting=0, pending=1, deadLetters=0], StreamState[length=1, waiting=0, pending=1, deadLetters=0], StreamState[length=1, waiting=0, pending=0, deadLetters=1]]
- 따로 보관한 뒤 Redis 예약 남음 false. A 재요청 QUEUED → 처리 결과 누적 {DEAD_LETTER=1, RETRY_DataAccessResourceFailureException=3, SUCCESS=1}

## 일시적 DB 실패 뒤 재시도 (재고 1)

- 첫 처리 실패: 스트림 StreamState[length=1, waiting=0, pending=1, deadLetters=0], Redis 예약 남음 true (ACK 도 보상도 하지 않음)
- 회수 뒤 다시 처리: 처리 결과 {RETRY_DataAccessResourceFailureException=1, SUCCESS=1}, 스트림 StreamState[length=1, waiting=0, pending=0, deadLetters=0], DB 발급 행 1

## 소비자 없음, 그 뒤 소비자 시작 (재고 1)

- 소비자 0 에서 A 요청: QUEUED. 스트림 StreamState[length=1, waiting=1, pending=0, deadLetters=0], DB 발급 행 0
- 소비자를 붙인 뒤: 처리 결과 {SUCCESS=1}, 스트림 StreamState[length=1, waiting=0, pending=0, deadLetters=0], DB 발급 행 1

## 대기와 pending (재고 2, 소비자 1)

- 소비자가 A 를 읽고 확정 전에 멈춘 사이 B 요청: 스트림 StreamState[length=2, waiting=1, pending=1, deadLetters=0] (A 는 pending, B 는 대기)
- 놓아준 뒤: 스트림 StreamState[length=2, waiting=0, pending=0, deadLetters=0], 처리 결과 {SUCCESS=2}

## DB 커밋 뒤 ACK 전 종료 흉내, 그 뒤 회수 (재고 1)

- 소비자 c1 이 커밋한 뒤 ACK 전에 Error: DB 발급 행 1, 스트림 StreamState[length=1, waiting=0, pending=1, deadLetters=0]
- c2 가 XAUTOCLAIM 으로 1건 가져와 다시 처리: 처리 결과 {ALREADY_ISSUED=1, SUCCESS=1}, DB 발급 행 1, issued_count 1, Redis [6], 스트림 StreamState[length=1, waiting=0, pending=0, deadLetters=0]

## 발행자가 예약 뒤 XADD 전에 종료 흉내 / 예약과 XADD 를 한 스크립트로 (재고 2)

- REDIS_STREAM, A: CRASHED. 스트림 길이 0, Redis 에 A 예약 true → A 재요청 DUPLICATE (쿠폰 없음)
- REDIS_STREAM_ATOMIC, B: QUEUED → 처리 결과 {SUCCESS=1}. DB 발급 행 1, Redis 에만 있는 회원 1 (A)

## XADD 실패 (스트림 키를 문자열로 막음, 재고 1)

- REDIS_STREAM_ATOMIC: ERROR, 예약 남음 false (스크립트에서 XADD 가 SADD 보다 먼저라 SADD 까지 가지 않음)
- REDIS_STREAM: ERROR, 예약 남음 false (예약 뒤 XADD 예외를 잡아 SREM)

## 같은 그룹의 소비자 2개 (재고 20, 요청 20)

- 처리 결과 {SUCCESS=20}, DB 발급 행 20, 스트림 StreamState[length=20, waiting=0, pending=0, deadLetters=0]


## 동시 요청: REDIS_STREAM, 재고보다 많은 요청 (재고 10, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 6/8/8 | 20 | 48 | StreamState[length=10, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 8/10 | 4/4/4 | 19 | 58 | StreamState[length=20, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 7/10 | 5/7/7 | 21 | 45 | StreamState[length=30, waiting=0, pending=0, deadLetters=0] |

## 동시 요청: REDIS_STREAM, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 6/9/9 | 22 | 204 | StreamState[length=50, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/5/6 | 17 | 180 | StreamState[length=100, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/6/6 | 42 | 261 | StreamState[length=150, waiting=0, pending=0, deadLetters=0] |

## 동시 요청: REDIS_STREAM, 동일 회원 중복 (재고 50, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 3/3/4 | 11 | 45 | StreamState[length=10, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 3/4/4 | 24 | 44 | StreamState[length=20, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 5/7/8 | 28 | 57 | StreamState[length=30, waiting=0, pending=0, deadLetters=0] |
## 처리 전에 스트림을 자름 (XTRIM MAXLEN 1, 재고 2)

- A, B 를 넣고 소비자 없이 자름: 지운 메시지 1건
- 소비자를 붙인 뒤: 처리 결과 {SUCCESS=1}, DB 발급 행 1, Redis 에만 있는 회원 1 (A)


---

## 2회차 실행: 동시 요청
### 동시 요청: REDIS_STREAM, 재고보다 많은 요청 (재고 10, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 6/8/8 | 29 | 56 | StreamState[length=10, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 9/10 | 5/7/8 | 27 | 59 | StreamState[length=20, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 8/10 | 4/12/13 | 26 | 50 | StreamState[length=30, waiting=0, pending=0, deadLetters=0] |

### 동시 요청: REDIS_STREAM, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 6/6/7 | 19 | 167 | StreamState[length=50, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 6/10/10 | 30 | 157 | StreamState[length=100, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/5/5 | 17 | 199 | StreamState[length=150, waiting=0, pending=0, deadLetters=0] |

### 동시 요청: REDIS_STREAM, 동일 회원 중복 (재고 50, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 5/7/8 | 23 | 98 | StreamState[length=10, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 2/3/3 | 13 | 44 | StreamState[length=20, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 2/3/3 | 11 | 33 | StreamState[length=30, waiting=0, pending=0, deadLetters=0] |
### 처리 전에 스트림을 자름 (XTRIM MAXLEN 1, 재고 2)

- A, B 를 넣고 소비자 없이 자름: 지운 메시지 1건
- 소비자를 붙인 뒤: 처리 결과 {SUCCESS=1}, DB 발급 행 1, Redis 에만 있는 회원 1 (A)


---

## 3회차 실행: 동시 요청
### 동시 요청: REDIS_STREAM, 재고보다 많은 요청 (재고 10, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 9/10 | 5/6/6 | 22 | 45 | StreamState[length=10, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 8/10 | 4/6/6 | 30 | 47 | StreamState[length=20, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=10, SOLD_OUT=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 3/5/5 | 15 | 49 | StreamState[length=30, waiting=0, pending=0, deadLetters=0] |

### 동시 요청: REDIS_STREAM, 재고 충분 (재고 50, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/9/9 | 56 | 168 | StreamState[length=50, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 5/6/6 | 21 | 165 | StreamState[length=100, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=50} | {SUCCESS=50} | 50 | 50 | 0 / 0 | 50/50 | 4/4/4 | 17 | 158 | StreamState[length=150, waiting=0, pending=0, deadLetters=0] |

### 동시 요청: REDIS_STREAM, 동일 회원 중복 (재고 50, 요청 50, 요청 스레드 50, 같은 그룹 소비자 4)

| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |
|---:|---|---|---:|---:|---|---:|---|---:|---:|---|
| 1 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 3/4/4 | 16 | 69 | StreamState[length=10, waiting=0, pending=0, deadLetters=0] |
| 2 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 3/4/4 | 12 | 44 | StreamState[length=20, waiting=0, pending=0, deadLetters=0] |
| 3 | {QUEUED=10, DUPLICATE=40} | {SUCCESS=10} | 10 | 10 | 0 / 0 | 10/10 | 2/4/4 | 17 | 34 | StreamState[length=30, waiting=0, pending=0, deadLetters=0] |
### 처리 전에 스트림을 자름 (XTRIM MAXLEN 1, 재고 2)

- A, B 를 넣고 소비자 없이 자름: 지운 메시지 1건
- 소비자를 붙인 뒤: 처리 결과 {SUCCESS=1}, DB 발급 행 1, Redis 에만 있는 회원 1 (A)

