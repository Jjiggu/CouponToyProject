# 6단계 Redis 재시작 실행 원본 (2026-10-19): 스트림·그룹·pending·예약이 남는가

프로젝트의 `coupon-toy-redis` 는 `--appendonly no --save ""` 이고 기존 db 0 데이터가 있어 재시작하지 않았다. 같은 이미지(`redis:7.2-alpine`, 7.2.13)로 별도 컨테이너를 띄워(CPU 1, 메모리 1GB, 빈 디렉터리를 `/data` 에 연결) `redis-cli` 로 실험했다.

순서: `FLUSHALL` → 그룹 만들기(`XGROUP CREATE … 0 MKSTREAM`) → 예약 3건(`SADD coupon:issued:1`)과 메시지 3건(`XADD`) → 소비자 c1 이 2건 읽고(`XREADGROUP COUNT 2`) 1건 ACK → 재시작 → 같은 항목 확인. 정상 종료(`docker restart`, SIGTERM)와 강제 종료(`docker kill -s KILL` 뒤 `docker start`)를 설정마다 한 번씩.

실행 스크립트: `build/lab-tmp/exp6-persist.sh` (저장소 밖)

```
## off: redis-server --appendonly no --save 
- 예약 3, XADD 3, 소비자 c1 이 2건 읽고 1건 ACK 한 뒤 (대기 1, pending 1):
  XLEN 3, 그룹 name coupon-issuers consumers 1 pending 1 last-delivered-id 1792420803258-0 entries-read 2 lag 1, pending 요약 1 1792420803258-0 1792420803258-0 c1 1, 예약 SCARD 3
- docker restart (SIGTERM, 정상 종료) 뒤:
  XLEN 0, 그룹 ERR no such key , pending 요약 NOGROUP No such key 'coupon:issue:stream' or consumer group 'coupon-issuers' , 예약 SCARD 0
- 예약 3, XADD 3, 소비자 c1 이 2건 읽고 1건 ACK 한 뒤 (대기 1, pending 1):
  XLEN 3, 그룹 name coupon-issuers consumers 1 pending 1 last-delivered-id 1792420804675-0 entries-read 2 lag 1, pending 요약 1 1792420804675-0 1792420804675-0 c1 1, 예약 SCARD 3
- docker kill -s KILL (강제 종료) 뒤:
  XLEN 0, 그룹 ERR no such key , pending 요약 NOGROUP No such key 'coupon:issue:stream' or consumer group 'coupon-issuers' , 예약 SCARD 0
## aof-everysec: redis-server --appendonly yes --appendfsync everysec --save 
- 예약 3, XADD 3, 소비자 c1 이 2건 읽고 1건 ACK 한 뒤 (대기 1, pending 1):
  XLEN 3, 그룹 name coupon-issuers consumers 1 pending 1 last-delivered-id 1792420806385-0 entries-read 2 lag 1, pending 요약 1 1792420806385-0 1792420806385-0 c1 1, 예약 SCARD 3
- docker restart (SIGTERM, 정상 종료) 뒤:
  XLEN 3, 그룹 name coupon-issuers consumers 1 pending 1 last-delivered-id 1792420806385-0 entries-read  lag , pending 요약 1 1792420806385-0 1792420806385-0 c1 1, 예약 SCARD 3
- 예약 3, XADD 3, 소비자 c1 이 2건 읽고 1건 ACK 한 뒤 (대기 1, pending 1):
  XLEN 3, 그룹 name coupon-issuers consumers 1 pending 1 last-delivered-id 1792420807688-0 entries-read 2 lag 1, pending 요약 1 1792420807688-0 1792420807688-0 c1 1, 예약 SCARD 3
- docker kill -s KILL (강제 종료) 뒤:
  XLEN 3, 그룹 name coupon-issuers consumers 1 pending 1 last-delivered-id 1792420807688-0 entries-read  lag , pending 요약 1 1792420807688-0 1792420807688-0 c1 1, 예약 SCARD 3
```
