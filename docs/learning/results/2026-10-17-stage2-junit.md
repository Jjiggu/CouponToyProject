# 2단계 JUnit 실행 원본 (2026-10-17): 비관적 락 vs 낙관적 락

`./gradlew test --tests '*OptimisticPessimisticLockTest'` 가 `build/lab/stage2-lock.md` 에 남긴 내용을 그대로 옮겼다.

실행 조건
- MySQL 8.0.36 (Docker, 1 CPU / 1GB), 격리 수준 기본값 REPEATABLE READ, `innodb_deadlock_detect=ON`, `innodb_lock_wait_timeout=50`
- DB `CouponToyProject_test` (`ddl-auto: create-drop`), Redis 7.2.13 db 1
- 테스트 JVM: macOS 호스트, OpenJDK 17.0.19, 8 cores, Hikari 최대 풀 10
- 동시 요청은 HTTP 없이 `LabService.issue()` 를 스레드에서 직접 호출. 스레드 수 = 요청 수
- 낙관적 락 재시도 기본값: 최대 5회, 지연 0 ~ min(100, 10 × 2^(n-1)) ms 무작위
- 같은 클래스를 3번 실행했다. 순서 고정·끼어든 쓰기 13개 케이스의 결과 종류는 3번 모두 같았고(어느 스레드가 이기는지만 달라짐), 아래는 1회차 값이다.

---

## 순서 고정: PESSIMISTIC, 재고 1, 서로 다른 회원 2명 (시도 분포 {1=2})

- 결과: [SUCCESS, SOLD_OUT]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [9, 11] ms
- 예외 타입: [CouponSoldOutException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739738, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... e c1_0.coupon_id=56 for update}
  - {trx_id=739737, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... e c1_0.coupon_id=56 for update}

## 순서 고정: PESSIMISTIC, 재고 10, 서로 다른 회원 2명 (시도 분포 {1=2})

- 결과: [SUCCESS, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [8, 5] ms
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 2
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739751, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... e c1_0.coupon_id=57 for update}
  - {trx_id=739750, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... e c1_0.coupon_id=57 for update}

## 순서 고정: PESSIMISTIC, 재고 10, 같은 회원 2번 (시도 분포 {1=2})

- 결과: [SUCCESS, UNIQUE_VIOLATION]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [13, 19] ms
- 예외 타입: [DataIntegrityViolationException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739764, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... e c1_0.coupon_id=58 for update}
  - {trx_id=739763, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... e c1_0.coupon_id=58 for update}

## 순서 고정: OPTIMISTIC, 재고 1, 서로 다른 회원 2명 (시도 분포 {1=2})

- 결과: [SUCCESS, OPTIMISTIC_CONFLICT]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [5, 11] ms
- 예외 타입: [ObjectOptimisticLockingFailureException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739777, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=59 and version=0}
  - {trx_id=739776, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=59 and version=0}

## 순서 고정: OPTIMISTIC, 재고 10, 서로 다른 회원 2명 (시도 분포 {1=2})

- 결과: [SUCCESS, OPTIMISTIC_CONFLICT]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [5, 7] ms
- 예외 타입: [ObjectOptimisticLockingFailureException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739794, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=60 and version=0}
  - {trx_id=739793, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=60 and version=0}

## 순서 고정: OPTIMISTIC, 재고 10, 같은 회원 2번 (시도 분포 {1=2})

- 결과: [UNIQUE_VIOLATION, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [7, 6] ms
- 예외 타입: [DataIntegrityViolationException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739810, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=61 and version=0}
  - {trx_id=739809, isolation=REPEATABLE READ, lock_mode=S, locked_table=coupon_issues, locked_index=uk_coupon_issue_coupon_member, waiting_query=insert into coupon_issues (cou ... 3,'2026-10-17 18:27:29.47658')}

## 순서 고정: OPTIMISTIC_RETRY, 재고 1, 서로 다른 회원 2명 (시도 분포 {1=1, 2=1})

- 결과: [SOLD_OUT, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [9, 3] ms
- 예외 타입: [CouponSoldOutException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739827, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=62 and version=0}
  - {trx_id=739826, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=62 and version=0}

## 순서 고정: OPTIMISTIC_RETRY, 재고 10, 서로 다른 회원 2명 (시도 분포 {1=1, 2=1})

- 결과: [SUCCESS, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [22, 4] ms
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 2
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739840, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=63 and version=0}
  - {trx_id=739839, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=63 and version=0}

## 순서 고정: OPTIMISTIC_RETRY, 재고 10, 같은 회원 2번 (시도 분포 {1=2})

- 결과: [SUCCESS, UNIQUE_VIOLATION]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [4, 8] ms
- 예외 타입: [DataIntegrityViolationException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=739854, isolation=REPEATABLE READ, lock_mode=S, locked_table=coupon_issues, locked_index=uk_coupon_issue_coupon_member, waiting_query=insert into coupon_issues (cou ... ,'2026-10-17 18:27:30.232467')}
  - {trx_id=739853, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... ere coupon_id=64 and version=0}

## 끼어든 쓰기: VERSIONED_ENTITY, 요청: OPTIMISTIC

- 요청이 기다린 SQL: update coupons set created_at= ... ere coupon_id=65 and version=0
- 결과: OPTIMISTIC_CONFLICT, 시도 분포 1=1
- 최종 issued_count: 1 (정상이라면 1), version: 1, coupon_issues 행: 0

## 끼어든 쓰기: VERSIONED_ENTITY, 요청: OPTIMISTIC_RETRY

- 요청이 기다린 SQL: update coupons set created_at= ... ere coupon_id=66 and version=0
- 결과: SUCCESS, 시도 분포 2=1
- 최종 issued_count: 2 (정상이라면 2), version: 2, coupon_issues 행: 1

## 끼어든 쓰기: BULK_UPDATE, 요청: OPTIMISTIC

- 요청이 기다린 SQL: update coupons set created_at= ... ere coupon_id=67 and version=0
- 결과: SUCCESS, 시도 분포 1=1
- 최종 issued_count: 1 (정상이라면 2), version: 1, coupon_issues 행: 1

## 끼어든 쓰기: COUPON_ENTITY, 요청: OPTIMISTIC

- 요청이 기다린 SQL: update coupons set created_at= ... ere coupon_id=68 and version=0
- 결과: SUCCESS, 시도 분포 1=1
- 최종 issued_count: 1 (정상이라면 2), version: 1, coupon_issues 행: 1

## 동시 요청: PESSIMISTIC, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 10/10 | 0 | 9 | 58/80/82 | 89 | 49 | 0 | 0 | {1=50} | 0 |
| 2 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 10/10 | 0 | 10 | 60/90/92 | 97 | 49 | 0 | 0 | {1=50} | 0 |
| 3 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 10/10 | 0 | 8 | 54/76/77 | 82 | 49 | 0 | 0 | {1=50} | 0 |

## 동시 요청: OPTIMISTIC, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 45/67/68 | 81 | 47 | 0 | 0 | {1=50} | 44 |
| 2 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 35/63/63 | 66 | 49 | 0 | 0 | {1=50} | 44 |
| 3 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 55/114/116 | 120 | 49 | 0 | 0 | {1=50} | 44 |

## 동시 요청: OPTIMISTIC_RETRY, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 9 | 141/149/163 | 166 | 93 | 0 | 0 | {1=5, 2=7, 3=35, 4=3} | 86 |
| 2 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 9 | 144/153/178 | 180 | 76 | 0 | 0 | {1=5, 2=19, 3=25, 4=1} | 72 |
| 3 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 9 | 140/148/151 | 155 | 85 | 0 | 0 | {1=6, 2=11, 3=33} | 77 |

## 동시 요청: PESSIMISTIC, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 23 | 125/214/221 | 226 | 49 | 0 | 0 | {1=50} | 0 |
| 2 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 23 | 83/153/157 | 161 | 49 | 0 | 0 | {1=50} | 0 |
| 3 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 16 | 100/190/198 | 204 | 49 | 0 | 0 | {1=50} | 0 |

## 동시 요청: OPTIMISTIC, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 39/72/73 | 76 | 49 | 0 | 0 | {1=50} | 44 |
| 2 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 41/67/68 | 73 | 48 | 0 | 0 | {1=50} | 44 |
| 3 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 43/69/70 | 75 | 48 | 0 | 0 | {1=50} | 44 |

## 동시 요청: OPTIMISTIC_RETRY, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=26, OPTIMISTIC_CONFLICT=24} | 26 | 26 | 아니오 | 아니오 | 아니오 | 26/50 | 0 | 26 | 267/320/356 | 359 | 191 | 0 | 0 | {1=6, 2=5, 3=5, 4=2, 5=32} | 173 |
| 2 | {SUCCESS=34, OPTIMISTIC_CONFLICT=16} | 34 | 34 | 아니오 | 아니오 | 아니오 | 34/50 | 0 | 32 | 222/282/297 | 299 | 175 | 0 | 0 | {1=7, 2=3, 3=6, 4=4, 5=30} | 163 |
| 3 | {SUCCESS=29, OPTIMISTIC_CONFLICT=21} | 29 | 29 | 아니오 | 아니오 | 아니오 | 29/50 | 0 | 28 | 229/307/328 | 332 | 185 | 0 | 0 | {1=6, 2=4, 3=6, 4=4, 5=30} | 169 |

## 동시 요청: PESSIMISTIC, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, DUPLICATE=31, UNIQUE_VIOLATION=9} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 5 | 86/109/110 | 116 | 49 | 0 | 0 | {1=50} | 0 |
| 2 | {SUCCESS=10, DUPLICATE=27, UNIQUE_VIOLATION=13} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 5 | 65/93/94 | 97 | 49 | 0 | 0 | {1=50} | 0 |
| 3 | {SUCCESS=10, DUPLICATE=28, UNIQUE_VIOLATION=12} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 5 | 62/86/87 | 90 | 49 | 0 | 0 | {1=50} | 0 |

## 동시 요청: OPTIMISTIC, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=5, DUPLICATE=8, UNIQUE_VIOLATION=2, DEADLOCK=5, OPTIMISTIC_CONFLICT=30} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 3 | 53/76/78 | 110 | 64 | 5 | 0 | {1=50} | 30 |
| 2 | {SUCCESS=5, DUPLICATE=8, UNIQUE_VIOLATION=5, DEADLOCK=1, OPTIMISTIC_CONFLICT=31} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 0 | 60/82/83 | 85 | 58 | 1 | 0 | {1=50} | 31 |
| 3 | {SUCCESS=5, DUPLICATE=10, UNIQUE_VIOLATION=4, DEADLOCK=4, OPTIMISTIC_CONFLICT=27} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 0 | 39/55/57 | 59 | 58 | 4 | 0 | {1=50} | 27 |

마지막 데드락:

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 18:27:35 281472306061184
*** (1) TRANSACTION:
TRANSACTION 742108, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 4 lock struct(s), heap size 1128, 2 row lock(s), undo log entries 1
MySQL thread id 6926, OS thread handle 281470061907840, query id 716280 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (92,'2026-10-17 18:27:35.44023',2450,'2026-10-17 18:27:35.44023')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 198 page no 5 n bits 88 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742108 lock mode S locks gap before rec
Record lock, heap no 14 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005c; asc        \;;
 1: len 8; hex 8000000000000993; asc         ;;
 2: len 8; hex 8000000000000d1a; asc         ;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 198 page no 5 n bits 88 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742108 lock_mode X locks gap before rec insert intention waiting
Record lock, heap no 14 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005c; asc        \;;
 1: len 8; hex 8000000000000993; asc         ;;
 2: len 8; hex 8000000000000d1a; asc         ;;


*** (2) TRANSACTION:
TRANSACTION 742105, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 6 lock struct(s), heap size 1128, 3 row lock(s), undo log entries 1
MySQL thread id 6930, OS thread handle 281472294498176, query id 716266 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (92,'2026-10-17 18:27:35.438587',2449,'2026-10-17 18:27:35.438587')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 198 page no 5 n bits 88 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742105 lock mode S locks gap before rec
Record lock, heap no 14 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005c; asc        \;;
 1: len 8; hex 8000000000000993; asc         ;;
 2: len 8; hex 8000000000000d1a; asc         ;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 198 page no 5 n bits 88 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742105 lock_mode X locks gap before rec insert intention waiting
Record lock, heap no 14 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005c; asc        \;;
 1: len 8; hex 8000000000000993; asc         ;;
 2: len 8; hex 8000000000000d1a; asc         ;;

*** WE ROLL BACK TRANSACTION (1)
```

## 동시 요청: OPTIMISTIC_RETRY, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, DUPLICATE=26, UNIQUE_VIOLATION=9, DEADLOCK=5} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 10 | 65/121/134 | 137 | 96 | 5 | 0 | {1=19, 2=15, 3=13, 4=3} | 50 |
| 2 | {SUCCESS=10, DUPLICATE=27, UNIQUE_VIOLATION=9, DEADLOCK=4} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 9 | 54/102/120 | 122 | 85 | 4 | 0 | {1=19, 2=21, 3=5, 4=5} | 46 |
| 3 | {SUCCESS=10, DUPLICATE=24, UNIQUE_VIOLATION=12, DEADLOCK=4} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 9 | 55/101/136 | 139 | 104 | 4 | 0 | {1=19, 2=20, 3=7, 4=4} | 46 |

마지막 데드락:

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 18:27:35 281472306061184
*** (1) TRANSACTION:
TRANSACTION 742354, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 7 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6931, OS thread handle 281470060851072, query id 718567 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (95,'2026-10-17 18:27:35.826803',2475,'2026-10-17 18:27:35.826803')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 198 page no 5 n bits 104 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742354 lock mode S locks gap before rec
Record lock, heap no 22 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005f; asc        _;;
 1: len 8; hex 80000000000009ad; asc         ;;
 2: len 8; hex 8000000000000dd1; asc         ;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 198 page no 5 n bits 104 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742354 lock_mode X locks gap before rec insert intention waiting
Record lock, heap no 22 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005f; asc        _;;
 1: len 8; hex 80000000000009ad; asc         ;;
 2: len 8; hex 8000000000000dd1; asc         ;;


*** (2) TRANSACTION:
TRANSACTION 742356, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 4 lock struct(s), heap size 1128, 2 row lock(s), undo log entries 1
MySQL thread id 6922, OS thread handle 281472163323776, query id 718582 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (95,'2026-10-17 18:27:35.829423',2475,'2026-10-17 18:27:35.829423')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 198 page no 5 n bits 104 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742356 lock mode S locks gap before rec
Record lock, heap no 22 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005f; asc        _;;
 1: len 8; hex 80000000000009ad; asc         ;;
 2: len 8; hex 8000000000000dd1; asc         ;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 198 page no 5 n bits 104 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 742356 lock_mode X locks gap before rec insert intention waiting
Record lock, heap no 22 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 800000000000005f; asc        _;;
 1: len 8; hex 80000000000009ad; asc         ;;
 2: len 8; hex 8000000000000dd1; asc         ;;

*** WE ROLL BACK TRANSACTION (2)
```

---

# 탐색 실행 (위와 같은 코드·조건, 먼저 한 번 실행)

비관적 락 '재고보다 많은 요청' 3회차에서 먼저 온 10명 중 9명만 당첨됐다(1명은 N명 밖). 위 실행에서는 3회 모두 10/10 이었다.

### 동시 요청: PESSIMISTIC, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 0/10 | 10 | 8 | 180/236/241 | 258 | 49 | 0 | 0 | {1=50} | 0 |
| 2 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 10/10 | 0 | 8 | 99/144/148 | 158 | 49 | 0 | 0 | {1=50} | 0 |
| 3 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 8/10 | 2 | 9 | 87/136/139 | 148 | 49 | 0 | 0 | {1=50} | 0 |

### 동시 요청: OPTIMISTIC, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=7, OPTIMISTIC_CONFLICT=43} | 7 | 7 | 아니오 | 아니오 | 아니오 | 2/10 | 5 | 4 | 66/100/103 | 113 | 40 | 0 | 0 | {1=50} | 43 |
| 2 | {SUCCESS=5, OPTIMISTIC_CONFLICT=45} | 5 | 5 | 아니오 | 아니오 | 아니오 | 1/10 | 4 | 0 | 72/109/110 | 117 | 43 | 0 | 0 | {1=50} | 45 |
| 3 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 50/82/83 | 92 | 45 | 0 | 0 | {1=50} | 44 |

### 동시 요청: OPTIMISTIC_RETRY, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 8 | 228/252/259 | 263 | 88 | 0 | 0 | {1=7, 2=9, 3=31, 4=3} | 80 |
| 2 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 1/10 | 9 | 7 | 207/219/241 | 246 | 89 | 0 | 0 | {1=7, 2=6, 3=33, 4=4} | 84 |
| 3 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 9 | 238/253/278 | 282 | 92 | 0 | 0 | {1=5, 2=9, 3=31, 4=5} | 86 |

### 동시 요청: PESSIMISTIC, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 39 | 104/196/203 | 206 | 49 | 0 | 0 | {1=50} | 0 |
| 2 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 24 | 119/275/286 | 294 | 49 | 0 | 0 | {1=50} | 0 |
| 3 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 24 | 92/176/182 | 186 | 49 | 0 | 0 | {1=50} | 0 |

### 동시 요청: OPTIMISTIC, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=7, OPTIMISTIC_CONFLICT=43} | 7 | 7 | 아니오 | 아니오 | 아니오 | 7/50 | 0 | 0 | 53/80/80 | 89 | 39 | 0 | 0 | {1=50} | 43 |
| 2 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 43/73/75 | 80 | 49 | 0 | 0 | {1=50} | 44 |
| 3 | {SUCCESS=6, OPTIMISTIC_CONFLICT=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 37/59/60 | 68 | 49 | 0 | 0 | {1=50} | 44 |

### 동시 요청: OPTIMISTIC_RETRY, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=30, OPTIMISTIC_CONFLICT=20} | 30 | 30 | 아니오 | 아니오 | 아니오 | 30/50 | 0 | 27 | 290/338/356 | 360 | 181 | 0 | 0 | {1=7, 2=4, 3=5, 4=4, 5=30} | 166 |
| 2 | {SUCCESS=28, OPTIMISTIC_CONFLICT=22} | 28 | 28 | 아니오 | 아니오 | 아니오 | 28/50 | 0 | 28 | 241/298/307 | 315 | 182 | 0 | 0 | {1=5, 2=6, 3=4, 4=3, 5=32} | 173 |
| 3 | {SUCCESS=25, OPTIMISTIC_CONFLICT=25} | 25 | 25 | 아니오 | 아니오 | 아니오 | 25/50 | 0 | 25 | 218/284/297 | 305 | 181 | 0 | 0 | {1=7, 2=6, 3=3, 4=4, 5=30} | 169 |

### 동시 요청: PESSIMISTIC, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, DUPLICATE=32, UNIQUE_VIOLATION=8} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 4 | 69/88/89 | 94 | 49 | 0 | 0 | {1=50} | 0 |
| 2 | {SUCCESS=10, DUPLICATE=24, UNIQUE_VIOLATION=16} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 5 | 64/98/100 | 105 | 49 | 0 | 0 | {1=50} | 0 |
| 3 | {SUCCESS=10, DUPLICATE=26, UNIQUE_VIOLATION=14} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 2 | 68/100/101 | 108 | 49 | 0 | 0 | {1=50} | 0 |

### 동시 요청: OPTIMISTIC, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=5, DUPLICATE=9, UNIQUE_VIOLATION=4, DEADLOCK=1, OPTIMISTIC_CONFLICT=31} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 0 | 35/53/54 | 58 | 44 | 1 | 0 | {1=50} | 31 |
| 2 | {SUCCESS=5, DUPLICATE=9, UNIQUE_VIOLATION=5, DEADLOCK=5, OPTIMISTIC_CONFLICT=26} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 0 | 39/63/63 | 67 | 77 | 5 | 0 | {1=50} | 26 |
| 3 | {SUCCESS=5, DUPLICATE=9, UNIQUE_VIOLATION=3, DEADLOCK=4, OPTIMISTIC_CONFLICT=29} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 0 | 33/52/53 | 56 | 62 | 4 | 0 | {1=50} | 29 |



### 동시 요청: OPTIMISTIC_RETRY, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, DUPLICATE=30, UNIQUE_VIOLATION=3, DEADLOCK=7} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 6 | 57/88/119 | 121 | 95 | 7 | 0 | {1=22, 2=18, 3=7, 4=3} | 41 |
| 2 | {SUCCESS=10, DUPLICATE=24, UNIQUE_VIOLATION=8, DEADLOCK=8} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 8 | 54/99/147 | 150 | 104 | 8 | 0 | {1=24, 2=16, 3=8, 4=2} | 38 |
| 3 | {SUCCESS=10, DUPLICATE=28, UNIQUE_VIOLATION=10, DEADLOCK=2} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 10 | 77/110/123 | 126 | 76 | 2 | 0 | {1=17, 2=21, 3=12} | 45 |



---

# 재시도 조건 변경: `COUPON_ISSUE_OPTIMISTIC_MAX_ATTEMPTS=10`

`COUPON_ISSUE_OPTIMISTIC_MAX_ATTEMPTS=10 ./gradlew test --tests '*OptimisticPessimisticLockTest.concurrentRequests'` 의 OPTIMISTIC_RETRY 결과만 옮겼다.

### 동시 요청: OPTIMISTIC_RETRY, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 10 | 288/304/310 | 315 | 89 | 0 | 0 | {1=6, 2=7, 3=35, 4=2} | 83 |
| 2 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 1/10 | 9 | 8 | 250/261/266 | 271 | 90 | 0 | 0 | {1=7, 2=7, 3=32, 4=4} | 83 |
| 3 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 10 | 149/157/160 | 164 | 90 | 0 | 0 | {1=6, 2=6, 3=36, 4=2} | 84 |

### 동시 요청: OPTIMISTIC_RETRY, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 49 | 307/531/593 | 599 | 218 | 0 | 0 | {1=6, 2=5, 3=4, 4=4, 5=4, 6=8, 7=7, 8=10, 9=2} | 209 |
| 2 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 50 | 273/441/474 | 478 | 199 | 0 | 0 | {1=8, 2=6, 3=2, 4=4, 5=3, 6=8, 7=11, 8=6, 9=2} | 198 |
| 3 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 48 | 244/495/512 | 517 | 202 | 0 | 0 | {1=6, 2=6, 3=4, 4=3, 5=10, 6=5, 7=4, 8=9, 9=1, 10=2} | 201 |

### 동시 요청: OPTIMISTIC_RETRY, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, DUPLICATE=25, UNIQUE_VIOLATION=9, DEADLOCK=6} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 9 | 73/144/159 | 163 | 91 | 6 | 0 | {1=21, 2=18, 3=9, 4=2} | 42 |
| 2 | {SUCCESS=10, DUPLICATE=26, UNIQUE_VIOLATION=11, DEADLOCK=3} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 8 | 65/91/92 | 97 | 62 | 3 | 0 | {1=25, 2=15, 3=10} | 35 |
| 3 | {SUCCESS=10, DUPLICATE=24, UNIQUE_VIOLATION=8, DEADLOCK=8} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 9 | 58/158/220 | 226 | 94 | 8 | 0 | {1=22, 2=14, 3=10, 4=3, 5=1} | 47 |

---

# 재시도 조건 변경: `COUPON_ISSUE_OPTIMISTIC_MAX_ATTEMPTS=20`

`COUPON_ISSUE_OPTIMISTIC_MAX_ATTEMPTS=20 ./gradlew test --tests '*OptimisticPessimisticLockTest.concurrentRequests'` 의 OPTIMISTIC_RETRY 결과만 옮겼다.

### 동시 요청: OPTIMISTIC_RETRY, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 9 | 150/164/182 | 192 | 79 | 0 | 0 | {1=6, 2=15, 3=22, 4=7} | 80 |
| 2 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 9 | 163/176/180 | 184 | 83 | 0 | 0 | {1=5, 2=12, 3=31, 4=1, 5=1} | 81 |
| 3 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 9 | 179/190/191 | 198 | 86 | 0 | 0 | {1=6, 2=9, 3=34, 4=1} | 80 |

### 동시 요청: OPTIMISTIC_RETRY, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 50 | 444/639/677 | 684 | 230 | 0 | 0 | {1=6, 2=5, 3=3, 4=5, 5=5, 6=3, 7=11, 8=9, 9=1, 10=2} | 216 |
| 2 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 48 | 253/516/546 | 549 | 190 | 0 | 0 | {1=8, 2=2, 3=7, 4=1, 5=12, 6=4, 7=7, 8=2, 9=6, 10=1} | 200 |
| 3 | {SUCCESS=50} | 50 | 50 | 아니오 | 아니오 | 예 | 50/50 | 0 | 49 | 262/493/510 | 519 | 211 | 0 | 0 | {1=5, 2=7, 3=4, 4=3, 5=4, 6=11, 7=4, 8=9, 9=2, 10=1} | 207 |

### 동시 요청: OPTIMISTIC_RETRY, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, DUPLICATE=28, UNIQUE_VIOLATION=9, DEADLOCK=3} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 8 | 67/122/131 | 134 | 73 | 3 | 0 | {1=21, 2=16, 3=9, 4=4} | 46 |
| 2 | {SUCCESS=10, DUPLICATE=23, UNIQUE_VIOLATION=12, DEADLOCK=5} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 8 | 70/114/147 | 150 | 106 | 5 | 0 | {1=23, 2=13, 3=10, 4=4} | 45 |
| 3 | {SUCCESS=10, DUPLICATE=24, UNIQUE_VIOLATION=13, DEADLOCK=3} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 9 | 73/117/146 | 150 | 91 | 3 | 0 | {1=25, 2=11, 3=11, 4=3} | 42 |

---

# 재시도 조건 변경: `COUPON_ISSUE_OPTIMISTIC_BACKOFF_BASE_MS=0 COUPON_ISSUE_OPTIMISTIC_BACKOFF_MAX_MS=0`

`COUPON_ISSUE_OPTIMISTIC_BACKOFF_BASE_MS=0 COUPON_ISSUE_OPTIMISTIC_BACKOFF_MAX_MS=0 ./gradlew test --tests '*OptimisticPessimisticLockTest.concurrentRequests'` 의 OPTIMISTIC_RETRY 결과만 옮겼다.

### 동시 요청: OPTIMISTIC_RETRY, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 9 | 275/298/299 | 303 | 85 | 0 | 0 | {1=6, 2=12, 3=27, 4=5} | 81 |
| 2 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 9 | 196/206/209 | 212 | 91 | 0 | 0 | {1=5, 2=16, 3=19, 4=9, 5=1} | 85 |
| 3 | {SUCCESS=10, SOLD_OUT=40} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 9 | 168/180/182 | 185 | 89 | 0 | 0 | {1=8, 2=15, 3=19, 4=4, 5=4} | 81 |

### 동시 요청: OPTIMISTIC_RETRY, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=23, OPTIMISTIC_CONFLICT=27} | 23 | 23 | 아니오 | 아니오 | 아니오 | 23/50 | 0 | 21 | 190/263/293 | 298 | 182 | 0 | 0 | {1=5, 2=8, 3=4, 4=3, 5=30} | 172 |
| 2 | {SUCCESS=23, OPTIMISTIC_CONFLICT=27} | 23 | 23 | 아니오 | 아니오 | 아니오 | 23/50 | 0 | 22 | 249/307/308 | 311 | 190 | 0 | 0 | {1=7, 2=3, 3=5, 4=4, 5=31} | 176 |
| 3 | {SUCCESS=24, OPTIMISTIC_CONFLICT=26} | 24 | 24 | 아니오 | 아니오 | 아니오 | 24/50 | 0 | 21 | 252/290/296 | 300 | 187 | 0 | 0 | {1=7, 2=3, 3=6, 4=4, 5=30} | 173 |

### 동시 요청: OPTIMISTIC_RETRY, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|
| 1 | {SUCCESS=10, DUPLICATE=20, UNIQUE_VIOLATION=14, DEADLOCK=6} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 8 | 109/180/186 | 189 | 99 | 6 | 0 | {1=25, 2=11, 3=9, 4=1, 5=4} | 48 |
| 2 | {SUCCESS=10, DUPLICATE=21, UNIQUE_VIOLATION=15, DEADLOCK=4} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 9 | 137/202/203 | 208 | 106 | 4 | 0 | {1=22, 2=14, 3=5, 4=8, 5=1} | 52 |
| 3 | {SUCCESS=10, DUPLICATE=23, UNIQUE_VIOLATION=10, DEADLOCK=7} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 9 | 85/132/147 | 152 | 103 | 7 | 0 | {1=24, 2=13, 3=10, 4=3} | 42 |

