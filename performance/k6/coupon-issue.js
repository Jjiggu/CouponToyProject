import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

// 실험 API(/lab, local 프로필)를 사용한다. setup 에서 회원과 쿠폰을 새로 만들고, teardown 에서 결과를 출력한다.
const method = __ENV.METHOD || 'SERIALIZABLE';
const scenarioName = __ENV.SCENARIO || 'smoke';
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const totalCount = Number(__ENV.TOTAL_COUNT || 100);
const memberCount = Number(__ENV.MEMBERS || 1000);
const duplicateUsers = Number(__ENV.DUPLICATE_USERS || 100);
const warmupRequests = Number(__ENV.WARMUP || 0);
// false 면 중복 확인 SELECT 를 건너뛴다 (격리 수준 방식 전용)
const duplicateCheck = (__ENV.DUPLICATE_CHECK || 'true') !== 'false';

// 202 는 접수일 뿐 발급 완료가 아니다.
// 202: 비동기 방식(넘기기만 하고 확정은 나중)
const handedOff = ['REDIS_PUBSUB', 'REDIS_STREAM', 'REDIS_STREAM_ATOMIC', 'REDIS_WRITE_BEHIND'].includes(method);
const expectedSuccessStatus = method === 'REDIS_ASYNC' || handedOff ? 202 : 201;
// 409: 거절(품절, 중복), 503: 트랜잭션 실패(데드락, 락 대기 초과) 또는 비동기 큐 포화
const expectedStatuses = new Set([expectedSuccessStatus, 409, 503]);
const jsonHeaders = { headers: { 'Content-Type': 'application/json' } };

const unexpectedStatusRate = new Rate('unexpected_status_rate');
const serverErrorRate = new Rate('server_error_rate');
const successCounter = new Counter('coupon_issue_success_count');
const conflictCounter = new Counter('coupon_issue_conflict_count');
const lockFailureCounter = new Counter('coupon_issue_lock_failure_count');
// setup/teardown 요청이 섞이는 http_req_duration 대신 발급 요청 응답 시간만 따로 모은다.
const issueDuration = new Trend('coupon_issue_http_duration', true);

const scenarios = {
  smoke: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 10),
    timeUnit: '1s',
    duration: __ENV.DURATION || '1m',
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 50),
    maxVUs: Number(__ENV.MAX_VUS || 200),
  },
  // 동시에 몰리는 요청: VUS 개가 ITERATIONS 건을 최대한 빨리 나눠 보낸다.
  burst: {
    executor: 'shared-iterations',
    iterations: Number(__ENV.ITERATIONS || memberCount),
    vus: Number(__ENV.VUS || 100),
    maxDuration: __ENV.MAX_DURATION || '5m',
  },
  step: {
    executor: 'ramping-arrival-rate',
    startRate: 100,
    timeUnit: '1s',
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 1000),
    maxVUs: Number(__ENV.MAX_VUS || 5000),
    stages: [
      { target: 100, duration: '3m' },
      { target: 300, duration: '3m' },
      { target: 500, duration: '3m' },
      { target: 800, duration: '3m' },
      { target: 1000, duration: '3m' },
      { target: 1500, duration: '3m' },
      { target: 2000, duration: '3m' },
    ],
  },
  spike: {
    executor: 'ramping-arrival-rate',
    startRate: 100,
    timeUnit: '1s',
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 1000),
    maxVUs: Number(__ENV.MAX_VUS || 5000),
    stages: [
      { target: 100, duration: '1m' },
      { target: 2000, duration: '30s' },
      { target: 100, duration: '1m' },
    ],
  },
  soak: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 700),
    timeUnit: '1s',
    duration: __ENV.DURATION || '30m',
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 1000),
    maxVUs: Number(__ENV.MAX_VUS || 5000),
  },
  soldout: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 500),
    timeUnit: '1s',
    duration: __ENV.DURATION || '10s',
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 500),
    maxVUs: Number(__ENV.MAX_VUS || 2000),
  },
  duplicate: {
    executor: 'shared-iterations',
    iterations: Number(__ENV.ITERATIONS || 20000),
    vus: Number(__ENV.VUS || 500),
    maxDuration: __ENV.MAX_DURATION || '5m',
  },
};

export const options = {
  scenarios: {
    [scenarioName]: scenarios[scenarioName],
  },
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    unexpected_status_rate: ['rate<0.001'],
    server_error_rate: ['rate<0.001'],
    coupon_issue_http_duration: [expectedSuccessStatus === 202 ? 'p(95)<100' : 'p(95)<500'],
  },
};

export function setup() {
  const members = postJson('/lab/members', { count: memberCount });
  const firstMemberId = members.firstMemberId;

  // 워밍업은 별도 쿠폰에 같은 방식으로 보낸다. 측정 대상 쿠폰과 섞이지 않는다.
  if (warmupRequests > 0) {
    const warmup = postJson('/lab/coupons', { name: `warmup-${method}`, totalCount: warmupRequests });
    for (let i = 0; i < warmupRequests; i += 1) {
      issue(warmup.couponId, firstMemberId + (i % memberCount), { warmup: 'true' });
    }
    // 비동기 방식은 워밍업 요청의 DB 확정이 모두 끝난 뒤 측정을 시작한다. 남은 확정이 측정과 섞이지 않게.
    waitConfirmed(warmup.couponId);
  }

  const coupon = postJson('/lab/coupons', { name: `k6-${method}-${scenarioName}`, totalCount });
  return { couponId: coupon.couponId, firstMemberId, startedAt: Date.now() };
}

export default function (data) {
  const iteration = exec.scenario.iterationInTest;
  const offset = scenarioName === 'duplicate'
    ? iteration % Math.min(duplicateUsers, memberCount)
    : iteration % memberCount;

  const response = issue(data.couponId, data.firstMemberId + offset, { method, scenario: scenarioName });
  issueDuration.add(response.timings.duration);

  const expected = expectedStatuses.has(response.status);
  const serverError = (response.status >= 500 && response.status !== 503) || response.status === 0;

  unexpectedStatusRate.add(!expected);
  serverErrorRate.add(serverError);

  if (response.status === expectedSuccessStatus) {
    successCounter.add(1);
  } else if (response.status === 409) {
    conflictCounter.add(1);
  } else if (response.status === 503) {
    lockFailureCounter.add(1);
  }

  check(response, {
    'status is expected': () => expected,
    'server did not fail': () => !serverError,
  });
}

// 진입점이 기록한 결과와 DB 상태. 비동기 방식은 확정이 끝날 때까지 다시 조회한 뒤 출력한다.
export function teardown(data) {
  const acceptedSeconds = (Date.now() - data.startedAt) / 1000;
  const unfinishedAtLastResponse = unfinished(labResult(data.couponId));
  const { result, confirmedAt } = waitConfirmed(data.couponId);
  const confirmedSeconds = (confirmedAt - data.startedAt) / 1000;
  const requests = Object.values(result.requests).reduce((sum, count) => sum + count, 0);
  console.log(`lab result (GET /lab/coupons/${data.couponId}/result)\n${JSON.stringify(result, null, 2)}`);
  console.log(`elapsed=${acceptedSeconds.toFixed(2)}s 접수 처리량=${(requests / acceptedSeconds).toFixed(1)} req/s `
    + `발급 완료 처리량=${(result.issueRows / confirmedSeconds).toFixed(1)} req/s `
    + `(확정까지 ${confirmedSeconds.toFixed(2)}s, 마지막 응답 시점 미완료 ${unfinishedAtLastResponse}, 남은 미완료 ${unfinished(result)})`);
}

// 아직 확정 결과가 없는 요청 수. REDIS_ASYNC 는 진입점이 아는 미완료(pending),
// Pub/Sub·Stream 은 발행자가 결과를 받지 못하므로 Redis 에만 예약이 있는 회원 수로 본다.
function unfinished(result) {
  if (method === 'REDIS_ASYNC') return result.pending;
  if (handedOff) return result.redisOnlyMembers;
  return 0;
}

// 줄지 않고 멈춘 것을 유실로 볼 때까지 기다리는 시간. Stream 은 실패한 메시지를 회수해 다시 처리하므로(기본 5초 뒤) 더 길게 둔다.
const stallMillis = method.startsWith('REDIS_STREAM') ? 10000 : 2000;
// 확정을 기다리며 결과 API 를 다시 조회하는 간격. 결과 API 는 발급 회원 전체를 읽으므로 큰 실행에서는 늘린다
const confirmPollSeconds = Number(__ENV.CONFIRM_POLL_MS || 50) / 1000;

// 미완료가 0 이 되거나, stallMillis 동안 줄지 않거나(유실), 120초가 지날 때까지 confirmPollSeconds 간격으로 다시 조회한다.
// 확정 시각은 마지막으로 미완료가 줄어든 때다(유실이면 그 뒤 기다린 시간은 넣지 않는다).
function waitConfirmed(couponId) {
  let result = labResult(couponId);
  let last = unfinished(result);
  let confirmedAt = Date.now();
  const deadline = Date.now() + 120000;
  while (unfinished(result) > 0 && Date.now() < deadline && Date.now() - confirmedAt < stallMillis) {
    sleep(confirmPollSeconds);
    result = labResult(couponId);
    if (unfinished(result) !== last) {
      last = unfinished(result);
      confirmedAt = Date.now();
    }
  }
  if (unfinished(result) === 0) confirmedAt = Date.now();
  return { result, confirmedAt };
}

function labResult(couponId) {
  return http.get(`${baseUrl}/lab/coupons/${couponId}/result`).json('response');
}

function issue(couponId, memberId, tags) {
  return http.post(
    `${baseUrl}/lab/coupons/${couponId}/issue`,
    JSON.stringify({ memberId, method, duplicateCheck }),
    { ...jsonHeaders, tags },
  );
}

function postJson(path, body) {
  const response = http.post(`${baseUrl}${path}`, JSON.stringify(body), jsonHeaders);
  if (response.status !== 201) {
    throw new Error(`${path} failed: ${response.status} ${response.body}`);
  }
  return response.json('response');
}
