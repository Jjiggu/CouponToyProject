# 9단계 JUnit 실행 기록 (2026-10-20, Mac, 실제 MySQL·Redis)

`./gradlew test --tests "*.WriteBehindIssueTest"` 4개 통과. 아래는 build/lab/stage9-write-behind.md 그대로.

## Redis 명단 유실 뒤 계속 요청 (재고 5)

- 5명 발급·적재 뒤 Redis 명단을 지우고 다른 5명 요청: 요청 결과 {QUEUED=10}
- DB 발급 행 10 (재고 5), 재고 초과 true. DB 에는 수량 검사가 없어 막지 못한다
- 대사: DB 에만 있는 회원 [1, 2, 3, 4, 5], issued_count 10, 재고 초과 true (되돌리지 않고 알려만 준다)

## 정상: 재고 10, 회원 20명이 두 번씩 (요청 40, 요청 스레드 40)

- 접수 직후: 요청 결과 {QUEUED=10, SOLD_OUT=20, DUPLICATE=10}, DB 발급 행 0, Redis 에만 있는 회원 10, 접수 p95 40.8ms
- 적재기 한 번: 10 건을 한 트랜잭션으로 INSERT. DB 발급 행 10, issued_count 0 (쿠폰 행은 그대로)
- 대사: Redis 에만 있던 회원 [], DB 에만 있는 회원 [] → issued_count 10, 검증 Checks[atMostOncePerMember=true, notOverIssued=true, issuedCountMatchesRows=true, fullyIssued=true]

## 커밋 뒤 ACK 전 종료 (재고 3)

- 다시 시작한 적재기가 pending 3 건을 다시 읽어 INSERT: 이미 있는 행이라 바뀐 것 없음. DB 발급 행 3, pending 0

## 적재 요청 기록 유실 (재고 5)

- 스트림을 비운 뒤 적재기: 0 건. DB 발급 행 0, Redis 에만 있는 회원 5
- 대사: Redis 명단에서 5 명을 DB 에 넣음 → DB 발급 행 5, issued_count 5

