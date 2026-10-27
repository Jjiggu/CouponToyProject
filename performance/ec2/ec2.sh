#!/bin/bash
# EC2 측정. Mac 에서 실행하고, 인스턴스는 SSH 로만 다룬다. 인스턴스 정보는 build/ec2/hosts.env (README 참고)
#   performance/ec2/ec2.sh setup        패키지 설치, DB 인스턴스에 MySQL·Redis 기동, jar·k6 스크립트 올리기 (코드가 바뀌면 다시)
#   performance/ec2/ec2.sh tps <이름>    쿠폰 1장의 따라간 최대 속도 측정. 결과는 build/ec2/<이름>/
#   performance/ec2/ec2.sh event <이름>  선착순 쿠폰 이벤트 오픈 시나리오(재고 < 회원, 몰림 → 유지 → 이탈 → 꼬리). 끝난 뒤 적재 완료·대사·최종 정합성 확인
#     METHOD=REDIS_WRITE_BEHIND_NO_TX PEAK=4000 RAMP_SEC=10 HOLD_MIN=2 DECAY_MIN=3 TAIL_RATE=800 TAIL_MIN=5 STOCK=7000 MEMBERS=100000 DOUBLE_CLICK=0.05
#     CONFIGS="pessimistic lua async pubsub stream wb wb10"  ROUNDS=2  RATES="450 500 550 600 650 700"  PESSIMISTIC_RATES="300 350 400 450"  WARM_RATE=200
set -u
cd "$(dirname "$0")/../.."
source build/ec2/hosts.env
SSH_OPTS="-i $KEY -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR -o ControlMaster=auto -o ControlPath=$HOME/.ssh/cm-%r@%h:%p -o ControlPersist=10m"
on() { local h=$1; shift; ssh $SSH_OPTS ubuntu@$h "$@"; }
put() { local h=$1; shift; scp -q $SSH_OPTS "${@:1:$#-1}" ubuntu@$h:"${@: -1}"; }
get() { scp -q $SSH_OPTS ubuntu@$1:"$2" "$3"; }

setup() {
  set -e
  # DB: 지금 Mac 과 같은 docker-compose.yml 에서 mysql·redis 만 띄운다 (각 1 CPU / 1GB). MySQL 클라이언트는 표본 기록용
  on $DB_PUB "command -v docker >/dev/null || { sudo apt-get update -q && sudo apt-get install -yq docker.io docker-compose-v2 mysql-client && sudo usermod -aG docker ubuntu; }"
  ssh $SSH_OPTS -O exit ubuntu@$DB_PUB 2> /dev/null || true  # 다음 접속부터 docker 그룹이 적용되도록 묶어 둔 연결을 끊는다
  put $DB_PUB docker-compose.yml performance/ec2/sampler.py .
  on $DB_PUB "printf '[client]\nuser=root\npassword=%s\nhost=127.0.0.1\n' $DB_PASSWORD > ~/.my.cnf && chmod 600 ~/.my.cnf && sudo MYSQL_ROOT_PASSWORD=$DB_PASSWORD docker compose up -d --wait mysql redis"
  # 앱: 발행자와 구독자·소비자 프로세스를 같은 jar 로 띄운다
  on $APP_PUB "command -v java >/dev/null || { sudo apt-get update -q && sudo apt-get install -yq openjdk-17-jre-headless; }; mkdir -p logs"
  ./gradlew bootJar -q
  put $APP_PUB build/libs/CouponToyProject-0.0.1-SNAPSHOT.jar app.jar
  # k6: Mac 과 같은 버전
  on $K6_PUB 'command -v k6 >/dev/null || { a=$(dpkg --print-architecture); curl -sSL https://github.com/grafana/k6/releases/download/v2.1.0/k6-v2.1.0-linux-$a.tar.gz | sudo tar xz -C /usr/local/bin --strip-components=1 k6-v2.1.0-linux-$a/k6; }'
  put $K6_PUB performance/k6/coupon-issue.js .
  on $DB_PUB "docker ps --format '{{.Names}} {{.Status}}'"; on $APP_PUB "java -version 2>&1 | head -1"; on $K6_PUB "k6 version"
}

q() { on $DB_PUB "mysql -N CouponToyProject_lab -e \"$1\""; }
start_jar() { # start_jar <로그 이름> <포트> <프로필>
  on $APP_PUB "nohup env SPRING_PROFILES_ACTIVE=$3 SPRING_DATASOURCE_URL='jdbc:mysql://$DB_PRIV:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
    SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD SPRING_DATA_REDIS_HOST=$DB_PRIV SPRING_DATA_REDIS_DATABASE=2 \
    SPRING_JPA_SHOW_SQL=false SPRING_JPA_PROPERTIES_HIBERNATE_SHOW_SQL=false \
    java -Duser.timezone=Asia/Seoul -Dserver.port=$2 -jar app.jar > logs/$1.log 2>&1 < /dev/null &"
  local i; for i in $(seq 1 120); do on $APP_PUB "curl -sf -m 2 localhost:$2/actuator/health" > /dev/null && return 0; sleep 1; done
  echo "$1 did not start"; on $APP_PUB "tail -20 logs/$1.log"; return 1
}
# pkill·pgrep 패턴은 [.] [r] 로 써서 그 명령을 돌리는 원격 셸 자신과는 맞지 않게 한다
stop_port() { on $APP_PUB "pkill -f -- '-Dserver[.]port=$1'; for i in \$(seq 1 30); do pgrep -f -- '-Dserver[.]port=$1' > /dev/null || exit 0; sleep 1; done"; }
time_wait() { on $1 "ss -Htan state time-wait | wc -l"; }
jiffies() { on $1 "head -1 /proc/stat"; }  # cpu user nice system idle iowait ...
busy() { echo "$1 $2" | awk '{t0=0; for(i=2;i<=11;i++) t0+=$i; t1=0; for(i=13;i<=22;i++) t1+=$i; printf "%.0f", 100*(1-(($16+$17)-($5+$6))/(t1-t0))}'; }  # 두 시점 사이 CPU 사용률(%)
drain() { local i; for i in $(seq 1 120); do [ $(time_wait $APP_PUB) -lt 1000 ] && [ $(time_wait $K6_PUB) -lt 1000 ] && return 0; sleep 1; done; echo "TIME_WAIT still high"; }
run_level() { # run_level <이름> <METHOD> <초당 요청 수>
  local name=$1 m=$2 rate=$3 n=$(( $3 * 30 ))
  drain
  on $DB_PUB "rm -f /tmp/$name.stop; nohup python3 sampler.py /tmp/$name /tmp/$name.stop > /dev/null 2>&1 < /dev/null &"
  local a0=$(jiffies $APP_PUB) k0=$(jiffies $K6_PUB)
  on $K6_PUB "env BASE_URL=http://$APP_PRIV:18080 METHOD=$m SCENARIO=smoke RATE=$rate DURATION=30s PRE_ALLOCATED_VUS=200 MAX_VUS=2000 \
    MEMBERS=$n TOTAL_COUNT=$n WARMUP=200 CONFIRM_POLL_MS=500 k6 run --quiet --no-color --summary-export /tmp/$name.json coupon-issue.js" > $L/$name.log 2>&1
  local ec=$? a1=$(jiffies $APP_PUB) k1=$(jiffies $K6_PUB)
  on $DB_PUB "touch /tmp/$name.stop; while pgrep -f 'sample[r].py /tmp/$name ' > /dev/null; do sleep 0.2; done"
  get $DB_PUB /tmp/$name.cpu $L/; get $DB_PUB /tmp/$name.tps $L/; get $K6_PUB /tmp/$name.json $L/
  local cid=$(q "SELECT MAX(coupon_id) FROM coupons")
  echo "$name exit=$ec coupon=$cid app_cpu=$(busy "$a0" "$a1") k6_cpu=$(busy "$k0" "$k1") created=$(q "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM coupons WHERE coupon_id=$cid")" | tee -a $L/runs.txt
}
config() { # config <설정> <회차> : 앱을 띄우고 데이터를 비운 뒤 예열(버림), 그다음 속도를 차례로
  local cfg=$1 r=$2 method consumer="" rates=${RATES:-"450 500 550 600 650 700"}
  case $cfg in
    pessimistic) method=PESSIMISTIC; rates=${PESSIMISTIC_RATES:-"300 350 400 450"} ;;
    lua) method=REDIS_LUA ;;
    async) method=REDIS_ASYNC ;;
    pubsub) method=REDIS_PUBSUB; consumer=pubsub-consumer ;;
    stream) method=REDIS_STREAM_ATOMIC; consumer=stream-consumer ;;
    wb) method=REDIS_WRITE_BEHIND; consumer=wb-loader ;;
    wb10) method=REDIS_WRITE_BEHIND_NO_TX; consumer=wb-loader ;;
  esac
  start_jar $cfg-r$r-pub 18080 local || return 1
  on $APP_PUB "curl -s -X DELETE localhost:18080/lab/data" > /dev/null
  [ -n "$consumer" ] && { start_jar $cfg-r$r-consumer 18081 $consumer || return 1; }
  run_level warm-$cfg-r$r $method ${WARM_RATE:-200}
  for rate in $rates; do run_level $cfg-$rate-r$r $method $rate; done
  [ -n "$consumer" ] && stop_port 18081
  stop_port 18080
}
tps() {
  L=build/ec2/${1:?결과 디렉터리 이름}; mkdir -p $L
  local r cfg
  for r in $(seq 1 ${ROUNDS:-2}); do
    for cfg in ${CONFIGS:-pessimistic lua async pubsub stream}; do config $cfg $r; done
  done
  echo DONE >> $L/runs.txt
  python3 performance/ec2/tps_report.py $L > $L/report.md
}

rcli() { on $DB_PUB "docker exec coupon-toy-redis redis-cli -n 2 $*"; }
event() {
  L=build/ec2/${1:?결과 디렉터리 이름}; mkdir -p $L
  local method=${METHOD:-REDIS_WRITE_BEHIND_NO_TX}
  local params="METHOD=$method PEAK=${PEAK:-4000} RAMP_SEC=${RAMP_SEC:-10} HOLD_MIN=${HOLD_MIN:-2} DECAY_MIN=${DECAY_MIN:-3} TAIL_RATE=${TAIL_RATE:-800} TAIL_MIN=${TAIL_MIN:-5} STOCK=${STOCK:-7000} MEMBERS=${MEMBERS:-100000} DOUBLE_CLICK=${DOUBLE_CLICK:-0.05}"
  echo "$params" > $L/params.txt
  # 1. 발행자와 적재기를 띄우고 실험 데이터를 비운다. 예열(초당 200건 30초, 따로 쿠폰)은 버린다
  start_jar event-pub 18080 local || return 1
  on $APP_PUB "curl -s -X DELETE localhost:18080/lab/data" > /dev/null
  start_jar event-loader 18081 wb-loader || return 1
  run_level warm-event $method 200
  put $K6_PUB performance/k6/coupon-event.js performance/ec2/host_sampler.sh .
  put $APP_PUB performance/ec2/host_sampler.sh .
  drain
  # 2. 표본 기록: DB 인스턴스(MySQL CPU 0.1초, 발급 행·커밋·Redis 명단 1초), 앱·k6 인스턴스(CPU, java 메모리 1초)
  on $DB_PUB "rm -f /tmp/event.stop; nohup python3 sampler.py /tmp/event /tmp/event.stop > /dev/null 2>&1 < /dev/null &"
  local h; for h in $APP_PUB $K6_PUB; do on $h "rm -f /tmp/event.stop; nohup bash host_sampler.sh /tmp/event.host /tmp/event.stop > /dev/null 2>&1 < /dev/null &"; done
  # 3. 부하
  on $K6_PUB "env BASE_URL=http://$APP_PRIV:18080 $params k6 run --quiet --no-color --summary-export /tmp/event.json coupon-event.js" > $L/event.log 2>&1
  local ec=$? cid=$(q "SELECT MAX(coupon_id) FROM coupons")
  echo "k6_exit=$ec coupon=$cid created=$(q "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') FROM coupons WHERE coupon_id=$cid")" > $L/final.txt
  # 4. 적재가 끝나기를 기다린다: DB 발급 행 수 = Redis 명단 수 (최대 300초)
  local t0=$(date +%s) rows scard i
  for i in $(seq 1 300); do
    rows=$(q "SELECT COUNT(*) FROM coupon_issues WHERE coupon_id=$cid"); scard=$(rcli SCARD coupon:issued:$cid)
    [ "$rows" = "$scard" ] && break; sleep 1
  done
  echo "drain_wait_s=$(( $(date +%s) - t0 )) rows=$rows scard=$scard" >> $L/final.txt
  # 5. 대사(issued_count 를 행 수로, Redis 에만 있는 회원 넣기)와 결과 API
  on $APP_PUB "curl -s -X POST localhost:18080/lab/write-behind/coupons/$cid/reconcile" > $L/reconcile.json
  on $APP_PUB "curl -s localhost:18080/lab/coupons/$cid/result" > $L/result.json
  # 6. 저장소를 직접 본다
  { echo "mysql_rows_distinct_issued_total=$(q "SELECT CONCAT_WS(',', COUNT(*), COUNT(DISTINCT member_id), (SELECT issued_count FROM coupons WHERE coupon_id=$cid), (SELECT total_count FROM coupons WHERE coupon_id=$cid)) FROM coupon_issues WHERE coupon_id=$cid")"
    echo "redis_scard=$(rcli SCARD coupon:issued:$cid)"
    echo "stream_len=$(rcli XLEN coupon:wb:stream)"
    echo "stream_pending=$(rcli XPENDING coupon:wb:stream coupon-wb-loaders | head -1)"
    echo "dead_letters=$(rcli XLEN coupon:wb:stream:dead)"; } >> $L/final.txt
  # 7. 표본 멈추고 가져오기, 앱 정리
  for h in $DB_PUB $APP_PUB $K6_PUB; do on $h "touch /tmp/event.stop"; done
  on $DB_PUB "while pgrep -f 'sample[r].py /tmp/event ' > /dev/null; do sleep 0.2; done"
  get $DB_PUB /tmp/event.cpu $L/; get $DB_PUB /tmp/event.tps $L/; get $APP_PUB /tmp/event.host $L/app.host; get $K6_PUB /tmp/event.host $L/k6.host; get $K6_PUB /tmp/event.json $L/
  stop_port 18081; stop_port 18080
  python3 performance/ec2/event_report.py $L > $L/report.md
}

case ${1:-} in
  setup) setup ;;
  tps) shift; tps "$@" ;;
  event) shift; event "$@" ;;
  *) sed -n 2,7p "$0"; exit 1 ;;
esac
