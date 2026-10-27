# 1단계 JUnit 실행 원본 (2026-10-15)

`./gradlew test --tests '*IsolationIssueCouponServiceTest'` 가 `build/lab/stage1-isolation.md` 에 남긴 내용을 그대로 옮겼다.

실행 조건
- MySQL 8.0.36 (Docker, 1 CPU / 1GB, `innodb_lock_wait_timeout=50`, `innodb_deadlock_detect=ON`, `innodb_autoinc_lock_mode=2`)
- DB `CouponToyProject_test` (`ddl-auto: create-drop`), Redis 7.2.13 db 1
- 테스트 JVM: macOS 호스트, OpenJDK 17.0.19, 8 cores
- 동시 요청은 HTTP 없이 `LabService.issue()` 를 스레드에서 직접 호출. 스레드 수 = 요청 수
- Hikari 최대 풀 기본값 10. 풀 크기별 결과는 맨 아래에 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` 로 바꿔 실행한 것
- 같은 클래스를 4번 실행했다. 순서 고정 6개 케이스의 결과 종류는 4번 모두 같았고(어느 스레드가 이기는지만 달라짐), 동시 요청 표는 아래 1회차 값이다.

---

## 순서 고정: REPEATABLE_READ, 재고 1, 서로 다른 회원 2명

- 결과: [SUCCESS, SUCCESS]
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=650203, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 1:55.}
  - {trx_id=650202, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 1:55.}

## 순서 고정: REPEATABLE_READ, 재고 10, 서로 다른 회원 2명

- 결과: [SUCCESS, SUCCESS]
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=650217, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 1:56.}
  - {trx_id=650216, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 1:56.}

## 순서 고정: SERIALIZABLE, 재고 1, 서로 다른 회원 2명

- 결과: [DEADLOCK, SUCCESS]
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758049656, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-15 20:21:56 281472306061184
*** (1) TRANSACTION:
TRANSACTION 650230, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 183, OS thread handle 281471701954432, query id 28672 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (5,'2026-10-15 20:21:56.489129',106,'2026-10-15 20:21:56.489129')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650230 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650230 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) TRANSACTION:
TRANSACTION 650229, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 182, OS thread handle 281471697727360, query id 28671 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (5,'2026-10-15 20:21:56.48913',105,'2026-10-15 20:21:56.48913')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650229 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650229 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
```

## 순서 고정: SERIALIZABLE, 재고 10, 서로 다른 회원 2명

- 결과: [DEADLOCK, SUCCESS]
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758049656, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-15 20:21:56 281472306061184
*** (1) TRANSACTION:
TRANSACTION 650242, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 183, OS thread handle 281471701954432, query id 28748 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (6,'2026-10-15 20:21:56.770331',108,'2026-10-15 20:21:56.770331')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650242 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650242 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) TRANSACTION:
TRANSACTION 650243, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 182, OS thread handle 281471697727360, query id 28747 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (6,'2026-10-15 20:21:56.770256',107,'2026-10-15 20:21:56.770256')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650243 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650243 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
```

## 순서 고정: REPEATABLE_READ, 재고 10, 같은 회원 2번

- 결과: [SUCCESS, UNIQUE_VIOLATION]
- 예외 타입: [DataIntegrityViolationException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=650255, isolation=REPEATABLE READ, lock_mode=S, locked_table=coupon_issues, locked_index=uk_coupon_issue_coupon_member, waiting_query=insert into coupon_issues (cou ... ,'202}
  - {trx_id=650254, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 1:56.}

## 순서 고정: SERIALIZABLE, 재고 10, 같은 회원 2번

- 결과: [SUCCESS, DEADLOCK]
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758049656, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758047232, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-15 20:21:57 281472306061184
*** (1) TRANSACTION:
TRANSACTION 650267, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 182, OS thread handle 281471697727360, query id 28892 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (8,'2026-10-15 20:21:57.31882',110,'2026-10-15 20:21:57.31882')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650267 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650267 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) TRANSACTION:
TRANSACTION 650266, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 181, OS thread handle 281471696670592, query id 28891 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (8,'2026-10-15 20:21:57.31882',110,'2026-10-15 20:21:57.31882')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650266 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 57 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 650266 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
```

## 동시 요청: REPEATABLE_READ, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=50} | 50 | 6 | 예 | 예 | 아니오 | 10/10 | 40 | 19 | 67/123/126 | 49 | 0 |
| 2 | {SUCCESS=50} | 50 | 6 | 예 | 예 | 아니오 | 10/10 | 40 | 19 | 102/182/208 | 49 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 예 | 예 | 아니오 | 10/10 | 40 | 28 | 122/252/255 | 49 | 0 |

## 동시 요청: SERIALIZABLE, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 43/86/91 | 81 | 44 |
| 2 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 65/113/114 | 84 | 44 |
| 3 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 42/82/83 | 89 | 44 |

## 동시 요청: REPEATABLE_READ, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=50} | 50 | 6 | 아니오 | 예 | 예 | 50/50 | 0 | 28 | 93/170/173 | 49 | 0 |
| 2 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 18 | 102/173/175 | 49 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 10 | 96/171/174 | 49 | 0 |

## 동시 요청: SERIALIZABLE, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 49/93/95 | 93 | 44 |
| 2 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 47/89/90 | 89 | 44 |
| 3 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 61/106/107 | 72 | 44 |

## 동시 요청: REPEATABLE_READ, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=10, DUPLICATE=23, UNIQUE_VIOLATION=17} | 10 | 2 | 아니오 | 예 | - | 10/10 | 0 | 9 | 63/87/88 | 32 | 0 |
| 2 | {SUCCESS=10, DUPLICATE=23, UNIQUE_VIOLATION=17} | 10 | 3 | 아니오 | 예 | - | 10/10 | 0 | 4 | 44/61/62 | 23 | 0 |
| 3 | {SUCCESS=10, DUPLICATE=23, UNIQUE_VIOLATION=17} | 10 | 3 | 아니오 | 예 | - | 10/10 | 0 | 7 | 46/70/70 | 26 | 0 |

## 동시 요청: SERIALIZABLE, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=6, DUPLICATE=8, DEADLOCK=36} | 6 | 6 | 아니오 | 아니오 | - | 6/10 | 0 | 0 | 43/68/70 | 79 | 36 |
| 2 | {SUCCESS=6, DUPLICATE=10, DEADLOCK=34} | 6 | 6 | 아니오 | 아니오 | - | 6/10 | 0 | 0 | 39/70/72 | 75 | 34 |
| 3 | {SUCCESS=5, DUPLICATE=8, DEADLOCK=37} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 0 | 40/66/69 | 77 | 37 |

---

# Hikari 최대 풀 5 로 바꾼 동시 요청 결과

### 동시 요청: REPEATABLE_READ, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 5)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=44, SOLD_OUT=6} | 44 | 10 | 예 | 예 | 아니오 | 10/10 | 34 | 43 | 160/224/225 | 30 | 0 |
| 2 | {SUCCESS=45, SOLD_OUT=5} | 45 | 10 | 예 | 예 | 아니오 | 10/10 | 35 | 25 | 91/176/179 | 33 | 0 |
| 3 | {SUCCESS=47, SOLD_OUT=3} | 47 | 10 | 예 | 예 | 아니오 | 10/10 | 37 | 17 | 104/185/188 | 46 | 0 |

### 동시 요청: SERIALIZABLE, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 5)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=10, SOLD_OUT=8, DEADLOCK=32} | 10 | 10 | 아니오 | 아니오 | 예 | 3/10 | 7 | 2 | 101/153/154 | 57 | 32 |
| 2 | {SUCCESS=10, SOLD_OUT=13, DEADLOCK=27} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 0 | 76/135/136 | 60 | 27 |
| 3 | {SUCCESS=10, SOLD_OUT=11, DEADLOCK=29} | 10 | 10 | 아니오 | 아니오 | 예 | 2/10 | 8 | 0 | 81/135/137 | 63 | 29 |

### 동시 요청: REPEATABLE_READ, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 5)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=50} | 50 | 11 | 아니오 | 예 | 예 | 50/50 | 0 | 21 | 104/171/173 | 49 | 0 |
| 2 | {SUCCESS=50} | 50 | 10 | 아니오 | 예 | 예 | 50/50 | 0 | 22 | 72/132/134 | 49 | 0 |
| 3 | {SUCCESS=50} | 50 | 11 | 아니오 | 예 | 예 | 50/50 | 0 | 28 | 84/157/160 | 49 | 0 |

### 동시 요청: SERIALIZABLE, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 5)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=14, DEADLOCK=36} | 14 | 14 | 아니오 | 아니오 | 아니오 | 14/50 | 0 | 0 | 86/164/171 | 87 | 36 |
| 2 | {SUCCESS=10, DEADLOCK=40} | 10 | 10 | 아니오 | 아니오 | 아니오 | 10/50 | 0 | 0 | 53/100/104 | 61 | 40 |
| 3 | {SUCCESS=13, DEADLOCK=37} | 13 | 13 | 아니오 | 아니오 | 아니오 | 13/50 | 0 | 0 | 59/119/122 | 74 | 37 |

### 동시 요청: REPEATABLE_READ, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 5)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=10, DUPLICATE=34, UNIQUE_VIOLATION=6} | 10 | 3 | 아니오 | 예 | - | 10/10 | 0 | 7 | 49/73/77 | 12 | 0 |
| 2 | {SUCCESS=10, DUPLICATE=34, UNIQUE_VIOLATION=6} | 10 | 5 | 아니오 | 예 | - | 10/10 | 0 | 7 | 39/61/63 | 8 | 0 |
| 3 | {SUCCESS=10, DUPLICATE=31, UNIQUE_VIOLATION=9} | 10 | 5 | 아니오 | 예 | - | 10/10 | 0 | 5 | 46/72/72 | 14 | 0 |

### 동시 요청: SERIALIZABLE, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 5)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=9, DUPLICATE=22, DEADLOCK=19} | 9 | 9 | 아니오 | 아니오 | - | 9/10 | 0 | 0 | 80/113/116 | 48 | 19 |
| 2 | {SUCCESS=10, DUPLICATE=18, DEADLOCK=22} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 0 | 45/80/84 | 57 | 22 |
| 3 | {SUCCESS=10, DUPLICATE=24, DEADLOCK=16} | 10 | 10 | 아니오 | 아니오 | - | 10/10 | 0 | 0 | 48/82/84 | 54 | 16 |

---

# Hikari 최대 풀 20 로 바꾼 동시 요청 결과

### 동시 요청: REPEATABLE_READ, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 20)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=50} | 50 | 3 | 예 | 예 | 아니오 | 10/10 | 40 | 33 | 151/216/228 | 49 | 0 |
| 2 | {SUCCESS=50} | 50 | 4 | 예 | 예 | 아니오 | 10/10 | 40 | 31 | 102/137/149 | 47 | 0 |
| 3 | {SUCCESS=50} | 50 | 3 | 예 | 예 | 아니오 | 10/10 | 40 | 31 | 115/163/165 | 49 | 0 |

### 동시 요청: SERIALIZABLE, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 20)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=3, DEADLOCK=47} | 3 | 3 | 아니오 | 아니오 | 아니오 | 0/10 | 3 | 0 | 60/88/89 | 54 | 47 |
| 2 | {SUCCESS=3, DEADLOCK=47} | 3 | 3 | 아니오 | 아니오 | 아니오 | 1/10 | 2 | 0 | 95/125/128 | 72 | 47 |
| 3 | {SUCCESS=3, DEADLOCK=47} | 3 | 3 | 아니오 | 아니오 | 아니오 | 1/10 | 2 | 0 | 92/113/115 | 73 | 47 |

### 동시 요청: REPEATABLE_READ, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 20)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=50} | 50 | 3 | 아니오 | 예 | 예 | 50/50 | 0 | 17 | 112/172/174 | 49 | 0 |
| 2 | {SUCCESS=50} | 50 | 3 | 아니오 | 예 | 예 | 50/50 | 0 | 29 | 118/162/165 | 49 | 0 |
| 3 | {SUCCESS=50} | 50 | 3 | 아니오 | 예 | 예 | 50/50 | 0 | 25 | 121/194/197 | 49 | 0 |

### 동시 요청: SERIALIZABLE, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 20)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=3, DEADLOCK=47} | 3 | 3 | 아니오 | 아니오 | 아니오 | 3/50 | 0 | 0 | 54/71/72 | 67 | 47 |
| 2 | {SUCCESS=3, DEADLOCK=47} | 3 | 3 | 아니오 | 아니오 | 아니오 | 3/50 | 0 | 0 | 62/85/87 | 78 | 47 |
| 3 | {SUCCESS=3, DEADLOCK=47} | 3 | 3 | 아니오 | 아니오 | 아니오 | 3/50 | 0 | 0 | 46/62/64 | 83 | 47 |

### 동시 요청: REPEATABLE_READ, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 20)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=10, DUPLICATE=13, UNIQUE_VIOLATION=27} | 10 | 1 | 아니오 | 예 | - | 10/10 | 0 | 7 | 49/82/84 | 48 | 0 |
| 2 | {SUCCESS=10, DUPLICATE=9, UNIQUE_VIOLATION=31} | 10 | 2 | 아니오 | 예 | - | 10/10 | 0 | 6 | 44/65/65 | 43 | 0 |
| 3 | {SUCCESS=10, DUPLICATE=12, UNIQUE_VIOLATION=28} | 10 | 2 | 아니오 | 예 | - | 10/10 | 0 | 8 | 52/69/69 | 45 | 0 |

### 동시 요청: SERIALIZABLE, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 20)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=3, DUPLICATE=2, DEADLOCK=45} | 3 | 3 | 아니오 | 아니오 | - | 3/10 | 0 | 0 | 47/63/64 | 59 | 45 |
| 2 | {SUCCESS=3, DUPLICATE=2, DEADLOCK=45} | 3 | 3 | 아니오 | 아니오 | - | 3/10 | 0 | 0 | 45/65/66 | 69 | 45 |
| 3 | {SUCCESS=3, DUPLICATE=5, DEADLOCK=42} | 3 | 3 | 아니오 | 아니오 | - | 3/10 | 0 | 0 | 49/65/67 | 67 | 42 |

---

# Hikari 최대 풀 50 로 바꾼 동시 요청 결과

### 동시 요청: REPEATABLE_READ, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 50)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=50} | 50 | 1 | 예 | 예 | 아니오 | 10/10 | 40 | 50 | 282/371/375 | 49 | 0 |
| 2 | {SUCCESS=50} | 50 | 2 | 예 | 예 | 아니오 | 10/10 | 40 | 49 | 92/160/162 | 48 | 0 |
| 3 | {SUCCESS=50} | 50 | 2 | 예 | 예 | 아니오 | 10/10 | 40 | 46 | 121/164/168 | 49 | 0 |

### 동시 요청: SERIALIZABLE, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 50)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=2, DEADLOCK=48} | 2 | 2 | 아니오 | 아니오 | 아니오 | 1/10 | 1 | 0 | 51/75/92 | 71 | 48 |
| 2 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | 아니오 | 0/10 | 1 | 0 | 64/69/70 | 50 | 49 |
| 3 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | 아니오 | 0/10 | 1 | 0 | 89/95/97 | 50 | 49 |

### 동시 요청: REPEATABLE_READ, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 50)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=50} | 50 | 1 | 아니오 | 예 | 예 | 50/50 | 0 | 49 | 76/114/117 | 49 | 0 |
| 2 | {SUCCESS=50} | 50 | 1 | 아니오 | 예 | 예 | 50/50 | 0 | 47 | 85/119/120 | 49 | 0 |
| 3 | {SUCCESS=50} | 50 | 1 | 아니오 | 예 | 예 | 50/50 | 0 | 49 | 100/131/135 | 49 | 0 |

### 동시 요청: SERIALIZABLE, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 50)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | 아니오 | 1/50 | 0 | 0 | 47/55/58 | 51 | 49 |
| 2 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | 아니오 | 1/50 | 0 | 0 | 54/64/65 | 50 | 49 |
| 3 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | 아니오 | 1/50 | 0 | 0 | 39/43/48 | 50 | 49 |

### 동시 요청: REPEATABLE_READ, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 50)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=10, UNIQUE_VIOLATION=40} | 10 | 1 | 아니오 | 예 | - | 10/10 | 0 | 9 | 44/58/59 | 59 | 0 |
| 2 | {SUCCESS=10, UNIQUE_VIOLATION=40} | 10 | 1 | 아니오 | 예 | - | 10/10 | 0 | 10 | 56/70/70 | 54 | 0 |
| 3 | {SUCCESS=10, UNIQUE_VIOLATION=40} | 10 | 1 | 아니오 | 예 | - | 10/10 | 0 | 10 | 51/68/69 | 54 | 0 |

### 동시 요청: SERIALIZABLE, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 50)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|
| 1 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | - | 1/10 | 0 | 0 | 44/54/54 | 50 | 49 |
| 2 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | - | 1/10 | 0 | 0 | 64/102/103 | 50 | 49 |
| 3 | {SUCCESS=1, DEADLOCK=49} | 1 | 1 | 아니오 | 아니오 | - | 1/10 | 0 | 0 | 64/90/91 | 50 | 49 |

