import http from 'k6/http';
import { check } from 'k6';
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

// 202 는 접수일 뿐 발급 완료가 아니다.
const expectedSuccessStatus = method === 'REDIS_EVENT' ? 202 : 201;
// 409: 거절(품절, 중복), 503: 트랜잭션 실패(데드락, 락 대기 초과)
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
    coupon_issue_http_duration: [method === 'REDIS_EVENT' ? 'p(95)<100' : 'p(95)<500'],
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

// 진입점이 기록한 결과와 DB 상태. 비동기 방식은 처리가 끝난 뒤 다시 조회해야 한다.
export function teardown(data) {
  const elapsedSeconds = (Date.now() - data.startedAt) / 1000;
  const result = http.get(`${baseUrl}/lab/coupons/${data.couponId}/result`).json('response');
  const requests = Object.values(result.requests).reduce((sum, count) => sum + count, 0);
  const completed = result.requests.SUCCESS || 0;
  console.log(`lab result (GET /lab/coupons/${data.couponId}/result)\n${JSON.stringify(result, null, 2)}`);
  console.log(`elapsed=${elapsedSeconds.toFixed(2)}s 접수 처리량=${(requests / elapsedSeconds).toFixed(1)} req/s `
    + `발급 완료 처리량(동기 SUCCESS)=${(completed / elapsedSeconds).toFixed(1)} req/s`);
}

function issue(couponId, memberId, tags) {
  return http.post(
    `${baseUrl}/lab/coupons/${couponId}/issue`,
    JSON.stringify({ memberId, method }),
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
