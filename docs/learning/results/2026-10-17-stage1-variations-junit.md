# 1단계 변형 실험 JUnit 실행 원본 (2026-10-17): READ COMMITTED, 중복 확인 SELECT 제거

`./gradlew test --tests '*IsolationIssueCouponServiceTest'` 가 `build/lab/stage1-isolation.md` 에 남긴 내용을 그대로 옮겼다.
기존 RR / SERIALIZABLE 케이스도 같은 실행에 함께 들어 있다.

실행 조건
- MySQL 8.0.36 (Docker, 1 CPU / 1GB), `innodb_deadlock_detect=ON`, `innodb_lock_wait_timeout=50`, `innodb_rollback_on_timeout=OFF`
- DB `CouponToyProject_test` (`ddl-auto: create-drop`), Redis 7.2.13 db 1
- 테스트 JVM: macOS 호스트, OpenJDK 17.0.19, 8 cores, Hikari 최대 풀 10
- 동시 요청은 HTTP 없이 `LabService.issue()` 를 스레드에서 직접 호출. 스레드 수 = 요청 수
- "중복 확인 SELECT 제거" 는 `duplicateCheck=false` 로 `existsByMemberIdAndCouponId` 를 건너뛴 것
- 같은 클래스를 3번 실행했다. 순서 고정 14개 케이스의 결과 종류는 3번 모두 같았고(어느 스레드가 이기는지만 달라짐), 아래는 1회차 값이다.

---

## 순서 고정: REPEATABLE_READ, 재고 1, 서로 다른 회원 2명

- 결과: [SUCCESS, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [6, 4] ms
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683173, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 5:01.}
  - {trx_id=683172, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 5:01.}

## 순서 고정: REPEATABLE_READ, 재고 10, 서로 다른 회원 2명

- 결과: [SUCCESS, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [9, 6] ms
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683187, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 5:01.}
  - {trx_id=683186, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 05:01}

## 순서 고정: REPEATABLE_READ, 재고 10, 같은 회원 2번

- 결과: [UNIQUE_VIOLATION, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [14, 8] ms
- 예외 타입: [DataIntegrityViolationException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683200, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 05:02}
  - {trx_id=683199, isolation=REPEATABLE READ, lock_mode=S, locked_table=coupon_issues, locked_index=uk_coupon_issue_coupon_member, waiting_query=insert into coupon_issues (cou ... 5,'20}

## 순서 고정: SERIALIZABLE, 재고 1, 서로 다른 회원 2명

- 결과: [SUCCESS, DEADLOCK]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [11, 8] ms
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 17:05:02 281472306061184
*** (1) TRANSACTION:
TRANSACTION 683212, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6272, OS thread handle 281470057680768, query id 270647 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (6,'2026-10-17 17:05:02.475462',106,'2026-10-17 17:05:02.475462')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683212 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683212 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) TRANSACTION:
TRANSACTION 683213, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6273, OS thread handle 281471691386752, query id 270646 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (6,'2026-10-17 17:05:02.475462',107,'2026-10-17 17:05:02.475462')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683213 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683213 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
```

## 순서 고정: SERIALIZABLE, 재고 10, 서로 다른 회원 2명

- 결과: [DEADLOCK, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [11, 12] ms
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 17:05:02 281472306061184
*** (1) TRANSACTION:
TRANSACTION 683226, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6273, OS thread handle 281471691386752, query id 270722 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (7,'2026-10-17 17:05:02.736899',109,'2026-10-17 17:05:02.736899')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683226 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683226 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) TRANSACTION:
TRANSACTION 683225, ACTIVE 0 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6272, OS thread handle 281470057680768, query id 270723 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (7,'2026-10-17 17:05:02.736893',108,'2026-10-17 17:05:02.736893')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683225 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683225 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
```

## 순서 고정: SERIALIZABLE, 재고 10, 같은 회원 2번

- 결과: [SUCCESS, DEADLOCK]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [13, 10] ms
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 17:05:03 281472306061184
*** (1) TRANSACTION:
TRANSACTION 683237, ACTIVE 1 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6273, OS thread handle 281471691386752, query id 270795 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (8,'2026-10-17 17:05:03.002618',110,'2026-10-17 17:05:03.002618')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683237 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683237 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) TRANSACTION:
TRANSACTION 683238, ACTIVE 1 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6272, OS thread handle 281470057680768, query id 270794 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (8,'2026-10-17 17:05:03.002666',110,'2026-10-17 17:05:03.002666')

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683238 lock mode S
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683238 lock_mode X insert intention waiting
Record lock, heap no 1 PHYSICAL RECORD: n_fields 1; compact format; info bits 0
 0: len 8; hex 73757072656d756d; asc supremum;;

*** WE ROLL BACK TRANSACTION (2)
```

## 순서 고정: READ_COMMITTED, 재고 1, 서로 다른 회원 2명

- 결과: [SUCCESS, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [4, 5] ms
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683251, isolation=READ COMMITTED, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 5:03.}
  - {trx_id=683250, isolation=READ COMMITTED, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... 5:03.}

## 순서 고정: READ_COMMITTED, 재고 10, 서로 다른 회원 2명

- 결과: [SUCCESS, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [4, 8] ms
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683264, isolation=READ COMMITTED, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... :03.3}
  - {trx_id=683263, isolation=READ COMMITTED, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... :03.3}

## 순서 고정: READ_COMMITTED, 재고 10, 같은 회원 2번

- 결과: [UNIQUE_VIOLATION, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [12, 10] ms
- 예외 타입: [DataIntegrityViolationException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683277, isolation=READ COMMITTED, lock_mode=S, locked_table=coupon_issues, locked_index=uk_coupon_issue_coupon_member, waiting_query=insert into coupon_issues (cou ... ,'202}
  - {trx_id=683276, isolation=READ COMMITTED, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... :03.5}

## 순서 고정: REPEATABLE_READ, 중복 확인 SELECT 제거, 재고 1, 서로 다른 회원 2명

- 결과: [SUCCESS, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [7, 4] ms
- 예외 타입: []
- coupon_issues 행: 2, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683290, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... :03.8}
  - {trx_id=683289, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... :03.8}

## 순서 고정: REPEATABLE_READ, 중복 확인 SELECT 제거, 재고 10, 같은 회원 2번

- 결과: [UNIQUE_VIOLATION, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [7, 5] ms
- 예외 타입: [DataIntegrityViolationException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=683302, isolation=REPEATABLE READ, lock_mode=S, locked_table=coupon_issues, locked_index=uk_coupon_issue_coupon_member, waiting_query=insert into coupon_issues (cou ... ,'202}
  - {trx_id=683301, isolation=REPEATABLE READ, lock_mode=X,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=update coupons set created_at= ... :04.0}

## 순서 고정: SERIALIZABLE, 중복 확인 SELECT 제거, 재고 1, 서로 다른 회원 2명

- 결과: [SUCCESS, DEADLOCK]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [10, 10] ms
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... s c1_}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... s c1_}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 17:05:04 281472306061184
*** (1) TRANSACTION:
TRANSACTION 683315, ACTIVE 0 sec starting index read
mysql tables in use 1, locked 1
LOCK WAIT 7 lock struct(s), heap size 1128, 3 row lock(s), undo log entries 1
MySQL thread id 6272, OS thread handle 281470057680768, query id 271253 192.168.65.1 root updating
update coupons set created_at='2026-10-17 17:05:04.326952',issued_count=1,name='stage1',total_count=1,updated_at='2026-10-17 17:05:04.551861' where coupon_id=14

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683315 lock mode S locks rec but not gap
Record lock, heap no 3 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000e; asc         ;;
 1: len 6; hex 0000000a6d2e; asc     m.;;
 2: len 7; hex 81000001120110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 80000001; asc     ;;
 5: len 8; hex 99bb23114404fd28; asc   # D  (;;
 6: len 8; hex 99bb23114404fd28; asc   # D  (;;
 7: len 6; hex 737461676531; asc stage1;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683315 lock_mode X locks rec but not gap waiting
Record lock, heap no 3 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000e; asc         ;;
 1: len 6; hex 0000000a6d2e; asc     m.;;
 2: len 7; hex 81000001120110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 80000001; asc     ;;
 5: len 8; hex 99bb23114404fd28; asc   # D  (;;
 6: len 8; hex 99bb23114404fd28; asc   # D  (;;
 7: len 6; hex 737461676531; asc stage1;;


*** (2) TRANSACTION:
TRANSACTION 683314, ACTIVE 0 sec starting index read
mysql tables in use 1, locked 1
LOCK WAIT 7 lock struct(s), heap size 1128, 3 row lock(s), undo log entries 1
MySQL thread id 6273, OS thread handle 281471691386752, query id 271252 192.168.65.1 root updating
update coupons set created_at='2026-10-17 17:05:04.326952',issued_count=1,name='stage1',total_count=1,updated_at='2026-10-17 17:05:04.551861' where coupon_id=14

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683314 lock mode S locks rec but not gap
Record lock, heap no 3 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000e; asc         ;;
 1: len 6; hex 0000000a6d2e; asc     m.;;
 2: len 7; hex 81000001120110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 80000001; asc     ;;
 5: len 8; hex 99bb23114404fd28; asc   # D  (;;
 6: len 8; hex 99bb23114404fd28; asc   # D  (;;
 7: len 6; hex 737461676531; asc stage1;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683314 lock_mode X locks rec but not gap waiting
Record lock, heap no 3 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000e; asc         ;;
 1: len 6; hex 0000000a6d2e; asc     m.;;
 2: len 7; hex 81000001120110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 80000001; asc     ;;
 5: len 8; hex 99bb23114404fd28; asc   # D  (;;
 6: len 8; hex 99bb23114404fd28; asc   # D  (;;
 7: len 6; hex 737461676531; asc stage1;;

*** WE ROLL BACK TRANSACTION (2)
```

## 순서 고정: SERIALIZABLE, 중복 확인 SELECT 제거, 재고 10, 서로 다른 회원 2명

- 결과: [DEADLOCK, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [7, 8] ms
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... s c1_}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... s c1_}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 17:05:04 281472306061184
*** (1) TRANSACTION:
TRANSACTION 683328, ACTIVE 0 sec starting index read
mysql tables in use 1, locked 1
LOCK WAIT 7 lock struct(s), heap size 1128, 3 row lock(s), undo log entries 1
MySQL thread id 6273, OS thread handle 281471691386752, query id 271327 192.168.65.1 root updating
update coupons set created_at='2026-10-17 17:05:04.581539',issued_count=1,name='stage1',total_count=10,updated_at='2026-10-17 17:05:04.807702' where coupon_id=15

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683328 lock mode S locks rec but not gap
Record lock, heap no 2 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000f; asc         ;;
 1: len 6; hex 0000000a6d3b; asc     m;;;
 2: len 7; hex 82000000870110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 8000000a; asc     ;;
 5: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 6: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 7: len 6; hex 737461676531; asc stage1;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683328 lock_mode X locks rec but not gap waiting
Record lock, heap no 2 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000f; asc         ;;
 1: len 6; hex 0000000a6d3b; asc     m;;;
 2: len 7; hex 82000000870110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 8000000a; asc     ;;
 5: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 6: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 7: len 6; hex 737461676531; asc stage1;;


*** (2) TRANSACTION:
TRANSACTION 683327, ACTIVE 0 sec starting index read
mysql tables in use 1, locked 1
LOCK WAIT 7 lock struct(s), heap size 1128, 3 row lock(s), undo log entries 1
MySQL thread id 6272, OS thread handle 281470057680768, query id 271328 192.168.65.1 root updating
update coupons set created_at='2026-10-17 17:05:04.581539',issued_count=1,name='stage1',total_count=10,updated_at='2026-10-17 17:05:04.807698' where coupon_id=15

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683327 lock mode S locks rec but not gap
Record lock, heap no 2 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000f; asc         ;;
 1: len 6; hex 0000000a6d3b; asc     m;;;
 2: len 7; hex 82000000870110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 8000000a; asc     ;;
 5: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 6: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 7: len 6; hex 737461676531; asc stage1;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683327 lock_mode X locks rec but not gap waiting
Record lock, heap no 2 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 800000000000000f; asc         ;;
 1: len 6; hex 0000000a6d3b; asc     m;;;
 2: len 7; hex 82000000870110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 8000000a; asc     ;;
 5: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 6: len 8; hex 99bb23114408dfa3; asc   # D   ;;
 7: len 6; hex 737461676531; asc stage1;;

*** WE ROLL BACK TRANSACTION (2)
```

## 순서 고정: SERIALIZABLE, 중복 확인 SELECT 제거, 재고 10, 같은 회원 2번

- 결과: [DEADLOCK, SUCCESS]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [14, 14] ms
- 예외 타입: [CannotAcquireLockException]
- coupon_issues 행: 1, coupons.issued_count: 1
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... s c1_}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... s c1_}

```text
LATEST DETECTED DEADLOCK
------------------------
2026-10-17 17:05:05 281472306061184
*** (1) TRANSACTION:
TRANSACTION 683340, ACTIVE 1 sec inserting
mysql tables in use 1, locked 1
LOCK WAIT 6 lock struct(s), heap size 1128, 3 row lock(s), undo log entries 1
MySQL thread id 6272, OS thread handle 281470057680768, query id 271396 192.168.65.1 root update
insert into coupon_issues (coupon_id,created_at,member_id,updated_at) values (16,'2026-10-17 17:05:05.053728',123,'2026-10-17 17:05:05.053728')

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683340 lock mode S locks rec but not gap
Record lock, heap no 3 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 8000000000000010; asc         ;;
 1: len 6; hex 0000000a6d48; asc     mH;;
 2: len 7; hex 810000008c0110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 8000000a; asc     ;;
 5: len 8; hex 99bb2311440cba27; asc   # D  ';;
 6: len 8; hex 99bb2311440cba27; asc   # D  ';;
 7: len 6; hex 737461676531; asc stage1;;


*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683340 lock mode S waiting
Record lock, heap no 2 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 8000000000000010; asc         ;;
 1: len 8; hex 800000000000007b; asc        {;;
 2: len 8; hex 8000000000000043; asc        C;;


*** (2) TRANSACTION:
TRANSACTION 683339, ACTIVE 1 sec starting index read
mysql tables in use 1, locked 1
LOCK WAIT 8 lock struct(s), heap size 1128, 4 row lock(s), undo log entries 1
MySQL thread id 6273, OS thread handle 281471691386752, query id 271398 192.168.65.1 root updating
update coupons set created_at='2026-10-17 17:05:04.834087',issued_count=1,name='stage1',total_count=10,updated_at='2026-10-17 17:05:05.057836' where coupon_id=16

*** (2) HOLDS THE LOCK(S):
RECORD LOCKS space id 147 page no 5 n bits 72 index uk_coupon_issue_coupon_member of table `CouponToyProject_test`.`coupon_issues` trx id 683339 lock_mode X locks rec but not gap
Record lock, heap no 2 PHYSICAL RECORD: n_fields 3; compact format; info bits 0
 0: len 8; hex 8000000000000010; asc         ;;
 1: len 8; hex 800000000000007b; asc        {;;
 2: len 8; hex 8000000000000043; asc        C;;


*** (2) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS space id 148 page no 4 n bits 72 index PRIMARY of table `CouponToyProject_test`.`coupons` trx id 683339 lock_mode X locks rec but not gap waiting
Record lock, heap no 3 PHYSICAL RECORD: n_fields 8; compact format; info bits 0
 0: len 8; hex 8000000000000010; asc         ;;
 1: len 6; hex 0000000a6d48; asc     mH;;
 2: len 7; hex 810000008c0110; asc        ;;
 3: len 4; hex 80000000; asc     ;;
 4: len 4; hex 8000000a; asc     ;;
 5: len 8; hex 99bb2311440cba27; asc   # D  ';;
 6: len 8; hex 99bb2311440cba27; asc   # D  ';;
 7: len 6; hex 737461676531; asc stage1;;

*** WE ROLL BACK TRANSACTION (1)
```

## 동시 요청: READ_COMMITTED, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 7 | 예 | 예 | 아니오 | 10/10 | 40 | 21 | 93/178/181 | 214 | 48 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 6 | 예 | 예 | 아니오 | 10/10 | 40 | 21 | 90/154/158 | 166 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 6 | 예 | 예 | 아니오 | 10/10 | 40 | 30 | 103/182/185 | 189 | 49 | 0 | 0 |

## 동시 요청: REPEATABLE_READ, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 6 | 예 | 예 | 아니오 | 10/10 | 40 | 31 | 115/206/210 | 215 | 49 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 5 | 예 | 예 | 아니오 | 10/10 | 40 | 33 | 90/152/156 | 161 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 예 | 예 | 아니오 | 10/10 | 40 | 21 | 92/156/160 | 165 | 49 | 0 | 0 |

## 동시 요청: SERIALIZABLE, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 39/68/68 | 77 | 81 | 44 | 0 |
| 2 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 36/68/72 | 78 | 80 | 44 | 0 |
| 3 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 1/10 | 5 | 0 | 39/69/76 | 80 | 82 | 44 | 0 |

## 동시 요청: SERIALIZABLE, 중복 확인 SELECT 제거, 재고보다 많은 요청 (재고 10, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=8, DEADLOCK=42} | 8 | 8 | 아니오 | 아니오 | 아니오 | 1/10 | 7 | 0 | 39/78/78 | 83 | 73 | 42 | 0 |
| 2 | {SUCCESS=8, DEADLOCK=42} | 8 | 8 | 아니오 | 아니오 | 아니오 | 1/10 | 7 | 0 | 45/110/144 | 158 | 77 | 42 | 0 |
| 3 | {SUCCESS=8, DEADLOCK=42} | 8 | 8 | 아니오 | 아니오 | 아니오 | 1/10 | 7 | 0 | 55/90/90 | 94 | 71 | 42 | 0 |

## 동시 요청: READ_COMMITTED, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 6 | 아니오 | 예 | 예 | 50/50 | 0 | 15 | 119/177/180 | 185 | 49 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 6 | 아니오 | 예 | 예 | 50/50 | 0 | 28 | 106/200/203 | 207 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 6 | 아니오 | 예 | 예 | 50/50 | 0 | 17 | 88/159/162 | 166 | 49 | 0 | 0 |

## 동시 요청: REPEATABLE_READ, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 16 | 155/247/250 | 257 | 49 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 22 | 97/146/148 | 153 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 22 | 68/127/130 | 133 | 49 | 0 | 0 |

## 동시 요청: SERIALIZABLE, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=5, DEADLOCK=45} | 5 | 5 | 아니오 | 아니오 | 아니오 | 5/50 | 0 | 0 | 34/65/65 | 69 | 83 | 45 | 0 |
| 2 | {SUCCESS=7, DEADLOCK=43} | 7 | 7 | 아니오 | 아니오 | 아니오 | 7/50 | 0 | 0 | 43/75/80 | 84 | 84 | 43 | 0 |
| 3 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 38/75/76 | 80 | 76 | 44 | 0 |

## 동시 요청: SERIALIZABLE, 중복 확인 SELECT 제거, 재고 충분 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=8, DEADLOCK=42} | 8 | 8 | 아니오 | 아니오 | 아니오 | 8/50 | 0 | 0 | 34/64/67 | 70 | 67 | 42 | 0 |
| 2 | {SUCCESS=9, DEADLOCK=41} | 9 | 9 | 아니오 | 아니오 | 아니오 | 9/50 | 0 | 0 | 38/81/86 | 90 | 76 | 41 | 0 |
| 3 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 39/73/74 | 79 | 65 | 44 | 0 |

## 동시 요청: READ_COMMITTED, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=10, DUPLICATE=28, UNIQUE_VIOLATION=12} | 10 | 2 | 아니오 | 예 | - | 10/10 | 0 | 9 | 43/61/61 | 65 | 23 | 0 | 0 |
| 2 | {SUCCESS=10, DUPLICATE=23, UNIQUE_VIOLATION=17} | 10 | 4 | 아니오 | 예 | - | 10/10 | 0 | 5 | 40/56/57 | 60 | 29 | 0 | 0 |
| 3 | {SUCCESS=10, DUPLICATE=24, UNIQUE_VIOLATION=16} | 10 | 3 | 아니오 | 예 | - | 10/10 | 0 | 3 | 39/56/60 | 63 | 23 | 0 | 0 |

## 동시 요청: REPEATABLE_READ, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=10, DUPLICATE=26, UNIQUE_VIOLATION=14} | 10 | 2 | 아니오 | 예 | - | 10/10 | 0 | 7 | 42/58/58 | 62 | 24 | 0 | 0 |
| 2 | {SUCCESS=10, DUPLICATE=24, UNIQUE_VIOLATION=16} | 10 | 2 | 아니오 | 예 | - | 10/10 | 0 | 6 | 34/48/48 | 51 | 25 | 0 | 0 |
| 3 | {SUCCESS=10, DUPLICATE=23, UNIQUE_VIOLATION=17} | 10 | 3 | 아니오 | 예 | - | 10/10 | 0 | 6 | 36/50/50 | 53 | 31 | 0 | 0 |

## 동시 요청: REPEATABLE_READ, 중복 확인 SELECT 제거, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=10, UNIQUE_VIOLATION=40} | 10 | 2 | 아니오 | 예 | - | 10/10 | 0 | 7 | 41/80/82 | 87 | 37 | 0 | 0 |
| 2 | {SUCCESS=10, UNIQUE_VIOLATION=40} | 10 | 3 | 아니오 | 예 | - | 10/10 | 0 | 6 | 43/61/62 | 66 | 40 | 0 | 0 |
| 3 | {SUCCESS=10, UNIQUE_VIOLATION=40} | 10 | 3 | 아니오 | 예 | - | 10/10 | 0 | 5 | 51/73/75 | 81 | 45 | 0 | 0 |

## 동시 요청: SERIALIZABLE, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=6, DUPLICATE=8, DEADLOCK=36} | 6 | 6 | 아니오 | 아니오 | - | 6/10 | 0 | 0 | 45/106/119 | 127 | 84 | 36 | 0 |
| 2 | {SUCCESS=5, DUPLICATE=9, DEADLOCK=36} | 5 | 5 | 아니오 | 아니오 | - | 5/10 | 0 | 0 | 38/62/64 | 67 | 72 | 36 | 0 |
| 3 | {SUCCESS=6, DUPLICATE=10, DEADLOCK=34} | 6 | 6 | 아니오 | 아니오 | - | 6/10 | 0 | 0 | 37/60/69 | 72 | 62 | 34 | 0 |

## 동시 요청: SERIALIZABLE, 중복 확인 SELECT 제거, 동일 회원 중복 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=6, UNIQUE_VIOLATION=11, DEADLOCK=33} | 6 | 6 | 아니오 | 아니오 | - | 6/10 | 0 | 0 | 33/57/58 | 64 | 79 | 33 | 0 |
| 2 | {SUCCESS=7, UNIQUE_VIOLATION=9, DEADLOCK=34} | 7 | 7 | 아니오 | 아니오 | - | 7/10 | 0 | 0 | 36/58/62 | 66 | 87 | 34 | 0 |
| 3 | {SUCCESS=6, UNIQUE_VIOLATION=8, DEADLOCK=36} | 6 | 6 | 아니오 | 아니오 | - | 6/10 | 0 | 0 | 47/79/80 | 84 | 84 | 36 | 0 |

