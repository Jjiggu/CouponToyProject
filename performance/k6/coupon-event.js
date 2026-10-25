import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

// 선착순 쿠폰 이벤트 오픈 시나리오. 정각 오픈에 사람이 몰렸다가, 품절을 확인하고 빠져나가고, 늦게 온 사람이 낮게 이어진다.
//   오픈      0 → PEAK            RAMP_SEC 초     정각 몰림. 재고는 보통 이 구간에 다 나간다
//   몰림 유지  PEAK               HOLD_MIN 분     품절 뒤에도 계속 누른다
//   빠져나감   PEAK → TAIL_RATE   DECAY_MIN 분    품절 확인 후 이탈
//   꼬리      TAIL_RATE           TAIL_MIN 분     늦게 온 사람
// 재고(STOCK)보다 회원(MEMBERS)이 훨씬 많고, 요청마다 회원을 무작위로 골라 같은 회원이 여러 번 누른다(연타).
// DOUBLE_CLICK 비율의 요청은 같은 회원이 같은 요청을 동시에 두 번 보낸다(더블클릭).
// 이 스크립트는 요청만 보낸다. 적재가 끝났는지와 최종 정합성은 실행 스크립트(performance/ec2/ec2.sh event)가 확인한다.
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const method = __ENV.METHOD || 'REDIS_WRITE_BEHIND_NO_TX';
const peak = Number(__ENV.PEAK || 4000);
const rampSec = Number(__ENV.RAMP_SEC || 10);
const holdMin = Number(__ENV.HOLD_MIN || 2);
const decayMin = Number(__ENV.DECAY_MIN || 3);
const tailRate = Number(__ENV.TAIL_RATE || 800);
const tailMin = Number(__ENV.TAIL_MIN || 5);
const stock = Number(__ENV.STOCK || 7000);
const memberCount = Number(__ENV.MEMBERS || 100000);
const doubleClick = Number(__ENV.DOUBLE_CLICK || 0.05);
const jsonHeaders = { 'Content-Type': 'application/json' };
const totalMinutes = Math.ceil((rampSec + 60 * (holdMin + decayMin + tailMin)) / 60);

// 발급 요청만 모은다(setup 의 회원·쿠폰 생성 요청은 섞지 않는다). minute 태그로 분 단위로도 나뉜다
const duration = new Trend('event_http_duration', true);
const accepted = new Counter('event_accepted');           // 202: 접수(DB 적재는 나중)
const rejected = new Counter('event_rejected');           // 409: 품절 또는 이미 받음
const serverErrors = new Counter('event_server_errors');  // 5xx 또는 응답 없음(연결 실패·시간 초과)
const unexpected = new Counter('event_unexpected');       // 그 밖의 상태
const doubles = new Counter('event_double_clicks');       // 더블클릭으로 보낸 쌍의 수

// k6 는 threshold 가 걸린 태그 조합만 요약에 따로 내보낸다. 분 단위 값을 얻으려고 항상 통과하는 threshold 를 건다
const thresholds = { event_server_errors: ['count==0'], event_unexpected: ['count==0'] };
for (let m = 0; m <= totalMinutes; m++) {
  thresholds[`event_http_duration{minute:${m}}`] = ['max>=0'];
  thresholds[`event_server_errors{minute:${m}}`] = ['count>=0'];
}

const sec = (minutes) => `${Math.round(minutes * 60)}s`;
export const options = {
  scenarios: {
    open: {
      // 정해진 속도로 보낸다. 서버가 느려지면 VU 를 더 쓰고, 그래도 모자라면 못 보낸 요청이 dropped_iterations 에 남는다
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: [
        { target: peak, duration: `${rampSec}s` },
        { target: peak, duration: sec(holdMin) },
        { target: tailRate, duration: sec(decayMin) },
        { target: tailRate, duration: sec(tailMin) },
      ],
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 400),
      maxVUs: Number(__ENV.MAX_VUS || 3000),
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
  setupTimeout: '180s', // 회원 10만 명 생성
};

export function setup() {
  const members = post('/lab/members', { count: memberCount });
  const coupon = post('/lab/coupons', { name: `event-${method}`, totalCount: stock });
  // startedAt 이 분 단위의 기준이다. 시나리오는 setup 이 끝난 직후 시작한다
  return { couponId: coupon.couponId, firstMemberId: members.firstMemberId, startedAt: Date.now() };
}

export default function (data) {
  const memberId = data.firstMemberId + Math.floor(Math.random() * memberCount);
  const tags = { minute: String(Math.floor((Date.now() - data.startedAt) / 60000)) };
  const request = ['POST', `${baseUrl}/lab/coupons/${data.couponId}/issue`, JSON.stringify({ memberId, method }), { headers: jsonHeaders, tags }];
  if (Math.random() < doubleClick) {
    // 같은 회원의 같은 요청 두 개를 동시에 보낸다. 둘 다 접수되면 안 된다(회원당 한 장)
    doubles.add(1, tags);
    http.batch([request, request]).forEach((res) => record(res, tags));
  } else {
    record(http.request(...request), tags);
  }
}

function record(res, tags) {
  duration.add(res.timings.duration, tags);
  if (res.status === 202) {
    accepted.add(1, tags);
  } else if (res.status === 409) {
    rejected.add(1, tags);
  } else if (res.status === 0 || res.status >= 500) {
    serverErrors.add(1, tags);
  } else {
    unexpected.add(1, tags);
  }
}

export function teardown(data) {
  console.log(`event coupon=${data.couponId} firstMember=${data.firstMemberId} members=${memberCount} stock=${stock}`);
}

function post(path, body) {
  const response = http.post(`${baseUrl}${path}`, JSON.stringify(body), { headers: jsonHeaders });
  if (response.status !== 201) {
    throw new Error(`${path} failed: ${response.status} ${response.body}`);
  }
  return response.json('response');
}
