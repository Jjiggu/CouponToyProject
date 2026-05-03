#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const args = parseArgs(process.argv.slice(2));
const inputDir = path.resolve(args.input || 'performance/results');
const outputPath = path.resolve(args.output || 'docs/coupon-performance-test/results/latest-summary.md');
const title = args.title || '쿠폰 발급 성능 테스트 결과 요약';
const existingOutput = fs.existsSync(outputPath) ? fs.readFileSync(outputPath, 'utf8') : '';

const strategies = ['pessimistic', 'redis-transactional', 'redis-event'];
const scenarios = ['smoke', 'step', 'spike', 'soak', 'soldout', 'duplicate'];

if (!fs.existsSync(inputDir)) {
  fail(`Input directory does not exist: ${inputDir}`);
}

const files = fs.readdirSync(inputDir)
  .filter((file) => file.endsWith('.json'))
  .sort();

if (files.length === 0) {
  fail(`No k6 summary JSON files found in ${inputDir}`);
}

const rows = files.map((file) => {
  const fullPath = path.join(inputDir, file);
  const data = JSON.parse(fs.readFileSync(fullPath, 'utf8'));
  return buildRow(file, fullPath, data);
});

const markdown = renderMarkdown(rows);
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, markdown, 'utf8');
console.log(`Wrote ${path.relative(process.cwd(), outputPath)}`);

function buildRow(file, fullPath, data) {
  const metrics = data.metrics || {};
  const metadata = parseMetadata(file);
  const p95 = metricValue(metrics, 'http_req_duration', 'p(95)');
  const p99 = metricValue(metrics, 'http_req_duration', 'p(99)');
  const avg = metricValue(metrics, 'http_req_duration', 'avg');
  const max = metricValue(metrics, 'http_req_duration', 'max');
  const checksRate = metricValue(metrics, 'checks', 'rate');
  const httpReqs = metricValue(metrics, 'http_reqs', 'count');
  const httpReqRate = metricValue(metrics, 'http_reqs', 'rate');
  const httpFailedRate = metricValue(metrics, 'http_req_failed', 'rate');
  const dropped = metricValue(metrics, 'dropped_iterations', 'count') || 0;
  const iterations = metricValue(metrics, 'iterations', 'count') || 0;
  const unexpectedRate = metricValue(metrics, 'unexpected_status_rate', 'rate');
  const serverErrorRate = metricValue(metrics, 'server_error_rate', 'rate');
  const successCount = metricValue(metrics, 'coupon_issue_success_count', 'count') || 0;
  const conflictCount = metricValue(metrics, 'coupon_issue_conflict_count', 'count') || 0;
  const thresholdFailures = evaluateFailures({
    strategy: metadata.strategy,
    p95,
    iterations,
    dropped,
    unexpectedRate,
    serverErrorRate,
  });
  const status = thresholdFailures.length === 0 ? 'PASS' : `FAIL (${thresholdFailures.join(', ')})`;

  return {
    file,
    fullPath,
    scenario: metadata.scenario,
    strategy: metadata.strategy,
    couponId: metadata.couponId,
    status,
    checksRate,
    httpReqs,
    httpReqRate,
    httpFailedRate,
    avg,
    p95,
    p99,
    max,
    iterations,
    dropped,
    unexpectedRate,
    serverErrorRate,
    successCount,
    conflictCount,
    thresholdFailures,
  };
}

function parseMetadata(file) {
  const basename = file.replace(/\.json$/i, '');
  const strategy = strategies.find((candidate) => basename.includes(candidate)) || 'unknown';
  const scenario = scenarios.find((candidate) => basename.includes(candidate)) || 'unknown';
  const couponMatch = basename.match(/coupon-?(\d+)/i) || basename.match(/couponId-?(\d+)/i);

  return {
    scenario,
    strategy,
    couponId: couponMatch ? couponMatch[1] : '-',
  };
}

function evaluateFailures(row) {
  const failures = [];
  const maxP95 = row.strategy === 'redis-event' ? 100 : 500;

  if (!Number.isFinite(row.p95)) {
    failures.push('p95_missing');
  } else if (row.p95 > maxP95) {
    failures.push(`p95>${maxP95}ms`);
  }

  if (Number.isFinite(row.unexpectedRate) && row.unexpectedRate >= 0.001) {
    failures.push('unexpected_status_rate>=0.1%');
  }

  if (Number.isFinite(row.serverErrorRate) && row.serverErrorRate >= 0.001) {
    failures.push('server_error_rate>=0.1%');
  }

  if (row.iterations > 0 && row.dropped / row.iterations >= 0.01) {
    failures.push('dropped_iterations>=1%');
  }

  return failures;
}

function metricValue(metrics, metricName, valueName) {
  const metric = metrics[metricName];
  if (!metric) {
    return undefined;
  }

  if (metric.values && typeof metric.values[valueName] === 'number') {
    return metric.values[valueName];
  }

  if (typeof metric[valueName] === 'number') {
    return metric[valueName];
  }

  if (valueName === 'rate' && typeof metric.value === 'number') {
    return metric.value;
  }

  return undefined;
}

function renderMarkdown(rows) {
  const now = new Date().toISOString();
  const passCount = rows.filter((row) => row.status === 'PASS').length;
  const failCount = rows.length - passCount;
  const inputRelative = path.relative(process.cwd(), inputDir) || '.';

  return `# ${title}

생성 시각: ${now}

원본 k6 summary JSON 경로: \`${inputRelative}\`

## 1. 전체 판정

| 전체 파일 수 | PASS | FAIL |
|---:|---:|---:|
| ${rows.length} | ${passCount} | ${failCount} |

## 2. 전략별 요약

| 시나리오 | 전략 | 판정 | 요청 수 | req/s | 성공 | 409 | p95(ms) | p99(ms) | max(ms) | checks | unexpected | server error | dropped |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
${rows.map(renderSummaryRow).join('\n')}

## 3. 판정 실패 상세

${renderThresholdFailures(rows)}

${preservedSection('## 4. 정합성 확인 기록', '## 5. 병목 관찰 기록', defaultConsistencySection())}

${preservedSection('## 5. 병목 관찰 기록', '## 6. 원본 파일', defaultBottleneckSection())}

## 6. 원본 파일

${rows.map((row) => `- \`${path.relative(process.cwd(), row.fullPath)}\``).join('\n')}
`;
}

function preservedSection(startHeading, nextHeading, fallback) {
  const preserved = extractSection(existingOutput, startHeading, nextHeading);
  return preserved || fallback;
}

function extractSection(content, startHeading, nextHeading) {
  if (!content) {
    return '';
  }

  const start = content.indexOf(startHeading);
  if (start === -1) {
    return '';
  }

  const end = content.indexOf(nextHeading, start + startHeading.length);
  if (end === -1) {
    return content.slice(start).trim();
  }

  return content.slice(start, end).trim();
}

function defaultConsistencySection() {
  return `## 4. 정합성 확인 기록

아래 항목은 k6 summary JSON만으로는 판단할 수 없으므로 테스트 후 직접 기록한다.

| 전략 | couponId | Redis SCARD | DB issue rows | coupons.issued_count | 중복 row | 정합성 판정 |
|---|---:|---:|---:|---:|---:|---|
| pessimistic | 1 | 0 |  |  |  |  |
| redis-transactional | 2 |  |  |  |  |  |
| redis-event | 3 |  |  |  |  |  |

확인 SQL과 Redis 명령은 \`docs/coupon-performance-test/04-consistency-check.md\`를 따른다.`;
}

function defaultBottleneckSection() {
  return `## 5. 병목 관찰 기록

Grafana \`Coupon Local Monitoring\` 대시보드에서 확인한 값을 기록한다.

| 전략 | 최대 안정 RPS | API p95 | DB p95 | Redis p95 | App CPU | MySQL CPU | Redis CPU | Hikari pending | Tomcat busy | Row lock waits | Event inflight/lag | 병목 판단 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| pessimistic |  |  |  |  |  |  |  |  |  |  | - |  |
| redis-transactional |  |  |  |  |  |  |  |  |  |  | - |  |
| redis-event |  |  |  |  |  |  |  |  |  |  |  |  |`;
}

function renderSummaryRow(row) {
  return [
    row.scenario,
    row.strategy,
    row.status,
    formatNumber(row.httpReqs, 0),
    formatNumber(row.httpReqRate, 2),
    formatNumber(row.successCount, 0),
    formatNumber(row.conflictCount, 0),
    formatNumber(row.p95, 2),
    formatNumber(row.p99, 2),
    formatNumber(row.max, 2),
    formatPercent(row.checksRate),
    formatPercent(row.unexpectedRate),
    formatPercent(row.serverErrorRate),
    formatNumber(row.dropped, 0),
  ].map(escapeCell).join(' | ').replace(/^/, '| ').replace(/$/, ' |');
}

function renderThresholdFailures(rows) {
  const failed = rows.filter((row) => row.thresholdFailures.length > 0);

  if (failed.length === 0) {
    return '판정 실패 없음.';
  }

  const lines = [
    '| 시나리오 | 전략 | 실패 기준 |',
    '|---|---|---|',
  ];

  for (const row of failed) {
    lines.push(`| ${escapeCell(row.scenario)} | ${escapeCell(row.strategy)} | ${escapeCell(row.thresholdFailures.join('<br>'))} |`);
  }

  return lines.join('\n');
}

function formatNumber(value, digits) {
  if (!Number.isFinite(value)) {
    return '-';
  }

  return value.toFixed(digits);
}

function formatPercent(value) {
  if (!Number.isFinite(value)) {
    return '-';
  }

  return `${(value * 100).toFixed(3)}%`;
}

function escapeCell(value) {
  return String(value).replace(/\|/g, '\\|');
}

function parseArgs(argv) {
  const parsed = {};

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];

    if (!arg.startsWith('--')) {
      continue;
    }

    const key = arg.slice(2);
    const next = argv[i + 1];

    if (!next || next.startsWith('--')) {
      parsed[key] = true;
      continue;
    }

    parsed[key] = next;
    i += 1;
  }

  return parsed;
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
