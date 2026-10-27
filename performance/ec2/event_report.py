# 이벤트 오픈 시나리오(ec2.sh event) 요약. 사용법: python3 performance/ec2/event_report.py build/ec2/<결과 디렉터리>
# 입력: params.txt, event.json(k6 요약), event.tps(1초: 시각 쿠폰 발급행 Com_commit Redis명단), event.cpu(0.1초: 시각 MySQL 누적 CPU ns),
#       app.host·k6.host(1초: 시각 jiffies 8칸 java RSS KB), final.txt, result.json, reconcile.json
# 판정 기준(미리 정함): 정합성 표가 모두 맞음 + 분마다 서버 오류 0, 응답 p95 < 100ms, MySQL CPU < 90%, 전체에서 k6 가 못 보낸 요청 0
import sys, os, json, bisect, datetime

D = sys.argv[1]
P = dict(kv.split('=') for kv in open(os.path.join(D, 'params.txt')).read().split())
stock, peak, tail = int(P['STOCK']), float(P['PEAK']), float(P['TAIL_RATE'])
ramp, hold, decay, tail_s = float(P['RAMP_SEC']), 60 * float(P['HOLD_MIN']), 60 * float(P['DECAY_MIN']), 60 * float(P['TAIL_MIN'])
end = ramp + hold + decay + tail_s
P95_MAX, MYSQL_MAX = 100, 90


def target(t):  # 시나리오 시작 t 초의 목표 req/s
    if t < ramp:
        return peak * t / ramp
    if t < ramp + hold:
        return peak
    if t < ramp + hold + decay:
        return peak + (tail - peak) * (t - ramp - hold) / decay
    return tail if t < end else 0


final, created = {}, None
for line in open(os.path.join(D, 'final.txt')):
    if 'created=' in line:
        created = line.split('created=')[1].strip()
        line = line.split('created=')[0]
    for kv in line.split():
        k, _, v = kv.partition('=')
        final[k] = v
cid = final['coupon']
t0 = datetime.datetime.strptime(created, '%Y-%m-%d %H:%M:%S.%f').timestamp()  # 측정 쿠폰을 만든 시각 ≈ 시나리오 시작
k6 = json.load(open(os.path.join(D, 'event.json')))['metrics']
result = json.load(open(os.path.join(D, 'result.json')))['response']
rec = json.load(open(os.path.join(D, 'reconcile.json')))['response']


def interp(points, x):
    xs = [a for a, _ in points]
    i = bisect.bisect_left(xs, x)
    if i <= 0:
        return points[0][1]
    if i >= len(points):
        return points[-1][1]
    (a, va), (b, vb) = points[i - 1], points[i]
    return va + (vb - va) * (x - a) / (b - a)


tps = [s for s in (l.split() for l in open(os.path.join(D, 'event.tps'))) if s[1] == cid]
rows = [(float(s[0]), int(s[2])) for s in tps]
scard = [(float(s[0]), int(s[4])) for s in tps if len(s) > 4 and s[4].isdigit()]
cpu = [(float(a), float(b)) for a, b in (l.split() for l in open(os.path.join(D, 'event.cpu')))]


def host(name):
    out = []
    for l in open(os.path.join(D, name)):
        s = l.split()
        if len(s) == 10:
            j = list(map(int, s[1:9]))
            out.append((float(s[0]), sum(j), j[3] + j[4], int(s[9])))  # 시각, 전체, idle+iowait, java RSS KB
    return out


def busy(samples, a, b):
    seg = [x for x in samples if a <= x[0] <= b]
    if len(seg) < 2 or seg[-1][1] == seg[0][1]:
        return None
    return 100 * (1 - (seg[-1][2] - seg[0][2]) / (seg[-1][1] - seg[0][1]))


app, k6h = host('app.host'), host('k6.host')
sub = lambda metric, m, stat: (k6.get(f'{metric}{{minute:{m}}}') or {}).get(stat)
f = lambda v, fmt='{:.0f}': '-' if v is None else fmt.format(v)
c = lambda name: k6.get(name, {}).get('count', 0)

print(f"# 이벤트 오픈 시나리오 결과: {P['METHOD']}\n")
print(f"재고 {stock}, 회원 {P['MEMBERS']}, 오픈 0 → {peak:.0f} req/s {ramp:.0f}초, 유지 {hold / 60:g}분, {tail:.0f} 까지 {decay / 60:g}분 동안 줄임, "
      f"꼬리 {tail:.0f} req/s {tail_s / 60:g}분, 더블클릭 {float(P['DOUBLE_CLICK']) * 100:g}%\n")
print(f"판정 기준: 분마다 서버 오류 0, 응답 p95 < {P95_MAX}ms, MySQL CPU < {MYSQL_MAX}%. 전체에서 k6 가 못 보낸 요청 0. 최종 정합성 표가 모두 맞음\n")
print('## 분 단위\n')
print('| 분 | 목표 req/s | 보낸 요청 | 응답 p50 / p95 / p99 / max ms | 서버 오류 | MySQL CPU | app CPU | app java 메모리 | k6 CPU | DB 발급 행(분 끝) | 미적재 최대 | 판정 |')
print('|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|')
stable = True
for m in range(int(end // 60) + 2):
    cnt = sub('event_http_duration', m, 'count')
    if not cnt:
        continue
    a, b = t0 + 60 * m, t0 + 60 * (m + 1)
    span = [target(t) for t in range(60 * m, min(60 * (m + 1), int(end)))] or [0]  # 그 분 동안 1초마다 본 목표
    lo, hi = min(span), max(span)
    mysql = (interp(cpu, b) - interp(cpu, a)) / 1e9 / 60 * 100
    rss = [x[3] for x in app if a <= x[0] <= b]
    lag = [interp(scard, x) - n for x, n in rows if a <= x <= b] if scard else []
    p95, errs = sub('event_http_duration', m, 'p(95)'), sub('event_server_errors', m, 'count') or 0
    ok = errs == 0 and p95 is not None and p95 < P95_MAX and mysql < MYSQL_MAX
    stable = stable and ok
    print(f"| {m} | {lo:.0f}~{hi:.0f} | {cnt:.0f} | {f(sub('event_http_duration', m, 'med'), '{:.1f}')} / {f(p95, '{:.1f}')} / "
          f"{f(sub('event_http_duration', m, 'p(99)'), '{:.1f}')} / {f(sub('event_http_duration', m, 'max'))} | {errs:.0f} | {mysql:.0f}% | "
          f"{f(busy(app, a, b))}% | {f(max(rss) / 1024 if rss else None)}MB | {f(busy(k6h, a, b))}% | {f(interp(rows, b))} | "
          f"{f(max(lag) if lag else None)} | {'충족' if ok else '**미달**'} |")

total = k6['event_http_duration']['count']
dropped = c('dropped_iterations')
sold_out = next((x - t0 for x, n in scard if n >= stock), None)
loaded = next((x - t0 for x, n in rows if n >= stock), None)
d = k6['event_http_duration']
print('\n## 전체\n')
print(f"- 보낸 요청 {total:.0f} (더블클릭 쌍 {c('event_double_clicks'):.0f}), k6 가 제때 못 보낸 요청 {dropped:.0f}")
print(f"- 응답: 202 접수 {c('event_accepted'):.0f}, 409 거절 {c('event_rejected'):.0f}, 서버 오류 {c('event_server_errors'):.0f}, 그 밖 {c('event_unexpected'):.0f}")
print(f"- 응답 시간 전체 p50 {d['med']:.1f}ms, p95 {d['p(95)']:.1f}ms, p99 {d['p(99)']:.1f}ms, max {d['max']:.0f}ms")
print(f"- 재고 소진(Redis 명단 {stock}) 시작 후 {f(sold_out, '{:.1f}')}초, DB 적재 완료 {f(loaded, '{:.1f}')}초 (1초 표본이라 ±1초)")
print(f"- 부하가 끝난 뒤 적재 완료를 기다린 시간 {final['drain_wait_s']}초")

rows_n, distinct_n, issued_n, _ = map(int, final['mysql_rows_distinct_issued_total'].split(','))
srv = result['requests']
checks = [
    ('202 접수 수 = 재고', c('event_accepted'), stock),
    ('서버 접수 기록(QUEUED) = 202 접수 수', srv.get('QUEUED', 0), c('event_accepted')),
    ('서버 기록 전체 = 보낸 요청', sum(srv.values()), total),
    ('Redis 명단 = 재고', int(final['redis_scard']), stock),
    ('DB 발급 행 = 재고', rows_n, stock),
    ('DB 발급 회원(중복 제거) = DB 발급 행', distinct_n, rows_n),
    ('대사 뒤 issued_count = DB 발급 행', issued_n, rows_n),
    ('Redis 에만 있는 회원', result['redisOnlyMembers'], 0),
    ('DB 에만 있는 회원', result['dbOnlyMembers'], 0),
    ('대사가 Redis 에서 채운 회원(적재기가 못 넣은 것)', len(rec['insertedFromRedis']), 0),
    ('적재 pending', int(final['stream_pending']), 0),
    ('따로 보관(dead letter)', int(final['dead_letters']), 0),
    ('서버 오류(5xx·응답 없음)', c('event_server_errors'), 0),
]
consistent = all(v == want for _, v, want in checks) and all(v in (True, None) for v in result['checks'].values())
print('\n## 최종 정합성 (적재 완료·대사 뒤)\n')
print('| 확인 | 값 | 기대 | |')
print('|---|---:|---:|---|')
for name, v, want in checks:
    print(f"| {name} | {v:.0f} | {want:.0f} | {'맞음' if v == want else '**다름**'} |")
print(f"| 결과 API 검증(회원당 한 번, 재고 이하, issued_count = 행, 전량 발급) | {result['checks']} | 모두 true | "
      f"{'맞음' if all(v in (True, None) for v in result['checks'].values()) else '**다름**'} |")
o = result['ordering']
print(f"\n- 선착순: 서버에 먼저 도착한 유효 요청 {min(stock, o['validRequests'])}건 중 당첨 {o['firstNValidWon']}, 그 밖에서 당첨 {o['winnersOutsideFirstN']}")
print(f"- 서버 요청 결과 {srv}, 서버가 잰 응답 p95 {result['latencyMs']['p95']:.1f}ms")
print(f"- 스트림 길이 {final['stream_len']} (예열 쿠폰 포함, ACK 해도 지우지 않음)")
print(f"\n## 판정\n\n- 정합성: {'통과' if consistent else '**실패**'}\n- 안정성: {'통과' if stable and dropped == 0 else '**실패**'} "
      f"(분 단위 기준 {'모두 충족' if stable else '미달 있음'}, 못 보낸 요청 {dropped:.0f})")
