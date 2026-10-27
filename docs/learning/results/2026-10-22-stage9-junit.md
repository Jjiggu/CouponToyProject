# 9단계 JUnit 실행 기록 (2026-10-22, Mac, 실제 MySQL·Redis): 따로 보관(dead letter) 추가 뒤

`./gradlew test --tests "*.WriteBehindIssueTest"` 5개 통과. 아래는 build/lab/stage9-write-behind.md 그대로. 이전 4개 기록: [2026-10-20](2026-10-20-stage9-junit.md)

## Redis 명단 유실 뒤 계속 요청 (재고 5)

- 5명 발급·적재 뒤 Redis 명단을 지우고 다른 5명 요청: 요청 결과 {QUEUED=10}
- DB 발급 행 10 (재고 5), 재고 초과 true. DB 에는 수량 검사가 없어 막지 못한다
- 대사: DB 에만 있는 회원 [1, 2, 3, 4, 5], issued_count 10, 재고 초과 true (되돌리지 않고 알려만 준다)

## 정상: 재고 10, 회원 20명이 두 번씩 (요청 40, 요청 스레드 40)

- 접수 직후: 요청 결과 {QUEUED=10, SOLD_OUT=20, DUPLICATE=10}, DB 발급 행 0, Redis 에만 있는 회원 10, 접수 p95 41.4ms
- 적재기 한 번: 10 건을 한 트랜잭션으로 INSERT. DB 발급 행 10, issued_count 0 (쿠폰 행은 그대로)
- 대사: Redis 에만 있던 회원 [], DB 에만 있는 회원 [] → issued_count 10, 검증 Checks[atMostOncePerMember=true, notOverIssued=true, issuedCountMatchesRows=true, fullyIssued=true]

## 커밋 뒤 ACK 전 종료 (재고 3)

- 다시 시작한 적재기가 pending 3 건을 다시 읽어 INSERT: 이미 있는 행이라 바뀐 것 없음. DB 발급 행 3, pending 0

## 계속 실패하는 메시지 (재고 3, DB 가 B 의 INSERT 만 거절)

- 첫 적재: 묶음 실패 → 한 건씩 다시 넣어 A, C 적재(DB 발급 행 2), B 는 pending
- 적재가 실패로 끝난 횟수 3, 그 다음 호출에서 B 를 따로 보관: {couponId=4, deliveries=4, error=org.springframework.jdbc.UncategorizedSQLException: PreparedStatementCallback; uncategorized SQLException for SQL [INSERT INTO coupon_issues (coupon_id, member_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE member_id = member_id]; SQL state [45000]; error code [164, memberId=35, originalId=1792678236620-0}
- 그 뒤 pending 0, DB 발급 행 2, Redis 에만 있는 회원 1 (예약은 그대로)
- 거절을 풀고 대사: Redis 명단에서 [35] 를 넣음 → DB 발급 행 3, issued_count 3

## 적재 요청 기록 유실 (재고 5)

- 스트림을 비운 뒤 적재기: 0 건. DB 발급 행 0, Redis 에만 있는 회원 5
- 대사: Redis 명단에서 5 명을 DB 에 넣음 → DB 발급 행 5, issued_count 5

