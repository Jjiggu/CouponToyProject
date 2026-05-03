import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

const strategy = __ENV.STRATEGY || 'pessimistic';
const scenarioName = __ENV.SCENARIO || 'smoke';
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID || '1';
const tokensFile = __ENV.TOKENS_FILE || '../data/tokens.csv';
const duplicateUsers = Number(__ENV.DUPLICATE_USERS || 5000);

const expectedSuccessStatus = strategy === 'redis-event' ? 202 : 201;
const expectedStatuses = new Set([expectedSuccessStatus, 409]);

const unexpectedStatusRate = new Rate('unexpected_status_rate');
const serverErrorRate = new Rate('server_error_rate');
const successCounter = new Counter('coupon_issue_success_count');
const conflictCounter = new Counter('coupon_issue_conflict_count');

const tokens = new SharedArray('tokens', () => {
  const lines = open(tokensFile)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('memberId,'));

  return lines.map((line) => {
    const [memberId, token] = line.split(',');
    return { memberId, token };
  });
});

const scenarios = {
  smoke: {
    executor: 'constant-arrival-rate',
    rate: Number(__ENV.RATE || 10),
    timeUnit: '1s',
    duration: __ENV.DURATION || '1m',
    preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 50),
    maxVUs: Number(__ENV.MAX_VUS || 200),
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
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    unexpected_status_rate: ['rate<0.001'],
    server_error_rate: ['rate<0.001'],
    http_req_duration: [strategy === 'redis-event' ? 'p(95)<100' : 'p(95)<500'],
  },
};

export default function () {
  const tokenEntry = pickToken();
  const url = `${baseUrl}/coupons/issue/${couponId}/${strategy}`;

  const response = http.post(url, null, {
    headers: {
      Authorization: `Bearer ${tokenEntry.token}`,
    },
    tags: {
      strategy,
      scenario: scenarioName,
    },
  });

  const expected = expectedStatuses.has(response.status);
  const serverError = response.status >= 500 || response.status === 0;

  unexpectedStatusRate.add(!expected);
  serverErrorRate.add(serverError);

  if (response.status === expectedSuccessStatus) {
    successCounter.add(1);
  } else if (response.status === 409) {
    conflictCounter.add(1);
  }

  check(response, {
    'status is expected': () => expected,
    'server did not fail': () => !serverError,
  });
}

function pickToken() {
  if (tokens.length === 0) {
    throw new Error(`No tokens loaded from ${tokensFile}`);
  }

  const iteration = exec.scenario.iterationInTest;
  const tokenIndex = scenarioName === 'duplicate'
    ? iteration % Math.min(duplicateUsers, tokens.length)
    : iteration % tokens.length;

  return tokens[tokenIndex];
}
