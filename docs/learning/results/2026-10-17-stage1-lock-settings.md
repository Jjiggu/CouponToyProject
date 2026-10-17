# 1단계 MySQL 락 설정 변형 JUnit 실행 원본 (2026-10-17): 데드락 감지 끄기, 락 대기 시간

`LAB_CHANGE_MYSQL_GLOBALS=true ./gradlew test --tests '*IsolationLockSettingsExperimentTest'` 가 `build/lab/stage1-lock-settings.md` 에 남긴 내용을 그대로 옮겼다.

실행 조건
- 위 변형 실험과 같은 환경. 실험마다 `SET GLOBAL innodb_deadlock_detect`, `SET GLOBAL innodb_lock_wait_timeout` 으로 바꾸고 끝나면 원래 값(ON, 50)으로 되돌렸다.
- `innodb_lock_wait_timeout` 은 커넥션 생성 시 세션 값으로 복사되므로, 바꿀 때마다 Hikari 커넥션을 버리고 최대 크기(10)까지 다시 채운 뒤 새 커넥션의 `@@SESSION.innodb_lock_wait_timeout` 을 확인했다.
- `innodb_rollback_on_timeout=OFF`: 락 대기 시간 초과 시 InnoDB 는 해당 문장만 되돌리고, 트랜잭션 롤백은 예외를 받은 Spring 이 한다.

---

## 순서 고정: SERIALIZABLE, 재고 10, 서로 다른 회원 2명, innodb_deadlock_detect=OFF, innodb_lock_wait_timeout=3

- 결과: [LOCK_WAIT_TIMEOUT, LOCK_WAIT_TIMEOUT]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [3067, 3067] ms
- 예외 타입: [PessimisticLockingFailureException, PessimisticLockingFailureException]
- coupon_issues 행: 0, coupons.issued_count: 0
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

## 순서 고정: SERIALIZABLE, 재고 10, 서로 다른 회원 2명, innodb_deadlock_detect=OFF, innodb_lock_wait_timeout=50

- 결과: [LOCK_WAIT_TIMEOUT, LOCK_WAIT_TIMEOUT]
- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: [50203, 50203] ms
- 예외 타입: [PessimisticLockingFailureException, PessimisticLockingFailureException]
- coupon_issues 행: 0, coupons.issued_count: 0
- 쿠폰 행 잠금 해제 직전 락 대기:
  - {trx_id=562947758048848, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}
  - {trx_id=562947758048040, isolation=SERIALIZABLE, lock_mode=S,REC_NOT_GAP, locked_table=coupons, locked_index=PRIMARY, waiting_query=select c1_0.coupon_id,c1_0.cre ... ns c1}

## 동시 요청: SERIALIZABLE, 재고 충분, innodb_deadlock_detect=OFF, innodb_lock_wait_timeout=3 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {LOCK_WAIT_TIMEOUT=50} | 0 | 0 | 아니오 | 아니오 | 아니오 | 0/50 | 0 | 0 | 9153/15226/15227 | 15262 | 50 | 0 | 49 |
| 2 | {LOCK_WAIT_TIMEOUT=50} | 0 | 0 | 아니오 | 아니오 | 아니오 | 0/50 | 0 | 0 | 9123/15215/15215 | 15235 | 50 | 0 | 50 |
| 3 | {LOCK_WAIT_TIMEOUT=50} | 0 | 0 | 아니오 | 아니오 | 아니오 | 0/50 | 0 | 0 | 9112/15173/15175 | 15184 | 50 | 0 | 49 |

## 동시 요청: REPEATABLE_READ, 재고 충분, innodb_deadlock_detect=OFF, innodb_lock_wait_timeout=3 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 17 | 122/176/178 | 200 | 49 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 27 | 96/164/167 | 172 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 22 | 140/195/197 | 203 | 49 | 0 | 0 |

## 동시 요청: SERIALIZABLE, 재고 충분, innodb_deadlock_detect=ON, innodb_lock_wait_timeout=1 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 42/86/90 | 98 | 73 | 44 | 0 |
| 2 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 39/71/76 | 88 | 76 | 44 | 0 |
| 3 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 42/88/89 | 92 | 85 | 44 | 0 |

## 동시 요청: REPEATABLE_READ, 재고 충분, innodb_deadlock_detect=ON, innodb_lock_wait_timeout=1 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 23 | 73/121/124 | 140 | 49 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 36 | 80/132/136 | 140 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 23 | 71/117/118 | 128 | 49 | 0 | 0 |

---

# 측정 결함이 있던 1차 실행 (보존용)

처음에는 커넥션을 버리기만 하고 다시 채우지 않았다. 설정을 바꾼 직후 1회차만 커넥션 생성을 기다리느라 동시성이 낮아져,
RR `issued_count` 와 SERIALIZABLE 성공 건수가 2·3회차와 다르게 나왔다(RR 13·17, SERIALIZABLE 17). 풀을 미리 채운 위 실행에서는 1회차도 2·3회차와 같았다.
순서 고정 결과(두 트랜잭션 모두 3083ms / 50143ms 에 타임아웃)는 1차와 같았다.

### 동시 요청: SERIALIZABLE, 재고 충분, innodb_deadlock_detect=OFF, innodb_lock_wait_timeout=3 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {LOCK_WAIT_TIMEOUT=50} | 0 | 0 | 아니오 | 아니오 | 아니오 | 0/50 | 0 | 0 | 9409/15500/15500 | 15561 | 50 | 0 | 50 |
| 2 | {LOCK_WAIT_TIMEOUT=50} | 0 | 0 | 아니오 | 아니오 | 아니오 | 0/50 | 0 | 0 | 9115/15196/15196 | 15223 | 50 | 0 | 50 |
| 3 | {LOCK_WAIT_TIMEOUT=50} | 0 | 0 | 아니오 | 아니오 | 아니오 | 0/50 | 0 | 0 | 9110/15200/15201 | 15210 | 50 | 0 | 50 |

### 동시 요청: REPEATABLE_READ, 재고 충분, innodb_deadlock_detect=OFF, innodb_lock_wait_timeout=3 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 13 | 아니오 | 예 | 예 | 50/50 | 0 | 22 | 143/211/215 | 219 | 39 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 6 | 아니오 | 예 | 예 | 50/50 | 0 | 25 | 114/180/182 | 190 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 27 | 96/174/175 | 182 | 49 | 0 | 0 |

### 동시 요청: SERIALIZABLE, 재고 충분, innodb_deadlock_detect=ON, innodb_lock_wait_timeout=1 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=17, DEADLOCK=33} | 17 | 17 | 아니오 | 아니오 | 아니오 | 17/50 | 0 | 4 | 92/150/161 | 188 | 57 | 33 | 0 |
| 2 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 43/75/75 | 86 | 71 | 44 | 0 |
| 3 | {SUCCESS=6, DEADLOCK=44} | 6 | 6 | 아니오 | 아니오 | 아니오 | 6/50 | 0 | 0 | 42/74/78 | 83 | 88 | 44 | 0 |

### 동시 요청: REPEATABLE_READ, 재고 충분, innodb_deadlock_detect=ON, innodb_lock_wait_timeout=1 (재고 50, 요청 50, 스레드 50, Hikari 최대 10)

| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 |
|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | {SUCCESS=50} | 50 | 17 | 아니오 | 예 | 예 | 50/50 | 0 | 40 | 86/153/155 | 159 | 35 | 0 | 0 |
| 2 | {SUCCESS=50} | 50 | 7 | 아니오 | 예 | 예 | 50/50 | 0 | 29 | 82/139/142 | 146 | 49 | 0 | 0 |
| 3 | {SUCCESS=50} | 50 | 5 | 아니오 | 예 | 예 | 50/50 | 0 | 33 | 83/143/147 | 151 | 49 | 0 | 0 |

