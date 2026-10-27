# Coupon Issue Performance Tests

`k6/coupon-issue.js` 는 실험 API(`/lab`, `local` 프로필)를 호출한다. 실행 한 번마다 setup 에서 회원과 쿠폰을 새로 만들고, teardown 에서 `GET /lab/coupons/{id}/result` 와 처리량(k6 출력 이름 `접수 처리량`, `발급 완료 처리량`)을 출력한다. 단계별 실험 조건은 [docs/learning](../docs/learning/README.md) 을 따른다.

## 1. 앱 실행

```bash
docker compose up -d mysql redis

# 기존 DB 와 Redis db 0 을 건드리지 않도록 별도 스키마와 Redis db 를 쓴다.
SERVER_PORT=18080 \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
SPRING_DATA_REDIS_DATABASE=2 SPRING_JPA_SHOW_SQL=false SPRING_JPA_PROPERTIES_HIBERNATE_SHOW_SQL=false \
./gradlew bootRun
```

`docker-compose.yml` 의 app 컨테이너는 `SPRING_PROFILES_ACTIVE=docker` 라서 실험 API 가 등록되지 않는다.

## 2. k6 실행

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | |
| `METHOD` | `SERIALIZABLE` | `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE`, `PESSIMISTIC`, `OPTIMISTIC`, `OPTIMISTIC_RETRY`, `REDIS_SET_ONLY`, `REDIS_MULTI_EXEC`, `REDIS_WATCH`, `REDIS_LUA`, `REDIS_ASYNC`, `REDIS_PUBSUB`, `REDIS_STREAM`, `REDIS_STREAM_ATOMIC` |
| `SCENARIO` | `smoke` | `burst`, `duplicate`, `smoke`, `soldout`, `step`, `spike`, `soak` |
| `TOTAL_COUNT` | `100` | 쿠폰 수량 |
| `MEMBERS` | `1000` | setup 에서 만들 회원 수 |
| `ITERATIONS`, `VUS` | 시나리오별 | `burst`, `duplicate` 의 총 요청 수와 동시 VU |
| `DUPLICATE_USERS` | `100` | `duplicate` 에서 요청을 나눠 쓸 회원 수 |
| `WARMUP` | `0` | 별도 쿠폰에 같은 방식으로 먼저 보낼 요청 수 |
| `CONFIRM_POLL_MS` | `50` | 비동기 방식에서 실제 지급을 기다리며 결과 API 를 다시 조회하는 간격 |
| `DUPLICATE_CHECK` | `true` | `false` 면 중복 확인 SELECT 를 건너뛴다. `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE` 에서만 |

```bash
BASE_URL=http://localhost:18080 METHOD=SERIALIZABLE SCENARIO=burst \
TOTAL_COUNT=100 MEMBERS=1000 ITERATIONS=1000 VUS=100 WARMUP=200 \
k6 run --summary-export performance/results/stage1-burst-serializable.json performance/k6/coupon-issue.js
```

- 상태 코드: 성공 201, 비동기 지급 성공 응답 202, 거절(품절·중복) 409, 트랜잭션 실패(데드락·락 대기 초과·버전 충돌)·비동기 큐 포화 503
- `OPTIMISTIC_RETRY` 의 재시도 상한과 지연은 앱 쪽 설정이다. bootRun 앞에 `COUPON_ISSUE_OPTIMISTIC_MAX_ATTEMPTS`(기본 5), `COUPON_ISSUE_OPTIMISTIC_BACKOFF_BASE_MS`(10), `COUPON_ISSUE_OPTIMISTIC_BACKOFF_MAX_MS`(100) 를 붙인다
- `REDIS_WATCH` 의 재시도 상한은 `COUPON_ISSUE_REDIS_WATCH_MAX_ATTEMPTS`(기본 10). 커넥션 풀이 없으면 `REDIS_MULTI_EXEC`·`REDIS_WATCH` 는 요청(시도)마다 새 Redis 연결을 맺는다. 이어서 실행할 때는 호스트의 TIME_WAIT 가 빠질 때까지 기다린다(`netstat -an -p tcp | grep -c TIME_WAIT`, 3단계 문서 4-8)
- `coupon_issue_http_duration` 은 지급 요청만의 응답 시간이다. `http_req_duration` 에는 setup/teardown 요청이 섞인다.
- `REDIS_ASYNC` 는 teardown 에서 결과 API 의 `pending`(지급 성공 응답을 받았지만 실제 지급 결과가 없는 요청)이 0 이 될 때까지 50ms 간격으로 다시 조회한다(최대 120초). `발급 완료 처리량` 은 그 시점까지로 계산한다
- `REDIS_PUBSUB` 은 구독자 프로세스를 따로 띄워야 실제 지급된다(같은 실행 방법에 `SPRING_PROFILES_ACTIVE=pubsub-consumer SERVER_PORT=18081`). teardown 은 결과 API 의 `redisOnlyMembers` 가 0 이 되거나 2초 동안 줄지 않을 때까지 기다린다. 줄지 않고 멈추면 유실로 본다
- `REDIS_STREAM`, `REDIS_STREAM_ATOMIC` 은 소비자 프로세스(`SPRING_PROFILES_ACTIVE=stream-consumer`)가 있어야 실제 지급된다. teardown 은 `redisOnlyMembers` 가 0 이 되거나 10초 동안 줄지 않을 때까지 기다린다(회수가 5초 뒤에 돌기 때문)
- `REDIS_ASYNC` 의 스레드 풀은 bootRun 앞에 `COUPON_ISSUE_ASYNC_POOL_SIZE`(기본 4), `COUPON_ISSUE_ASYNC_QUEUE_CAPACITY`(50000) 를 붙여 바꾼다

## 3. 선착순 쿠폰 이벤트 오픈 시나리오

`k6/coupon-event.js` 는 쿠폰 이벤트의 트래픽 모양을 흉내 낸다. 정각 오픈에 몰렸다가(오픈 → 몰림 유지), 품절을 확인하고 빠져나가고(이탈), 늦게 온 사람이 낮게 이어진다(꼬리). 재고보다 회원이 훨씬 많고, 회원을 무작위로 골라 같은 회원이 여러 번 누르며(연타), 일부는 같은 요청을 동시에 두 번 보낸다(더블클릭). 이 스크립트는 요청만 보낸다. 적재 완료 대기, 대사, 최종 정합성 확인과 표본 기록은 `ec2/ec2.sh event` 가 한다([ec2/README.md](ec2/README.md)).

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `METHOD` | `REDIS_WRITE_BEHIND_NO_TX` | 지급 방식 |
| `PEAK`, `RAMP_SEC` | `4000`, `10` | 0 에서 최대 req/s 까지 올리는 시간(초) |
| `HOLD_MIN` | `2` | 최대 속도 유지(분) |
| `TAIL_RATE`, `DECAY_MIN` | `800`, `3` | 최대에서 꼬리 속도까지 줄이는 시간(분) |
| `TAIL_MIN` | `5` | 꼬리 속도 유지(분) |
| `STOCK`, `MEMBERS` | `7000`, `100000` | 재고, 회원 수(실험 API 한도 20만) |
| `DOUBLE_CLICK` | `0.05` | 같은 요청을 동시에 두 번 보내는 비율 |

요약에는 지급 요청 응답 시간·요청 수·서버 오류가 분 단위(`minute` 태그)로도 나뉘어 나온다.
