# 쿠폰 1장의 따라간 최대 속도 요약. 사용법: python3 performance/ec2/tps_report.py build/ec2/<결과 디렉터리>   (그 안의 runs.txt 의 실행 전부, warm- 제외)
import sys, re, json, os, datetime, bisect, collections
D = sys.argv[1]
SYNC = {'pessimistic', 'lua'}
LABEL = {'pessimistic': '비관적 락', 'lua': '동기 Lua', 'async': '비동기 (작업 스레드 4)', 'pubsub': 'Pub/Sub (구독자 프로세스 1)', 'stream': 'Stream 한 스크립트 (소비자 프로세스 1)', 'wb': 'Write-behind (적재기 프로세스 1)', 'wb10': 'Write-behind, 회원 확인 트랜잭션 없음 (적재기 프로세스 1)'}
W0, W1 = 5.0, 28.0  # 측정 시작 뒤 이 구간을 보내기 정상 구간으로 본다. 끝을 보내기가 끝나는 30초에 두면 1초 표본 사이 보간이 멈춘 뒤 값과 섞여 약 1% 낮게 나온다

def interp(points, x):
    xs = [p[0] for p in points]
    i = bisect.bisect_left(xs, x)
    if i <= 0: return points[0][1]
    if i >= len(points): return points[-1][1]
    (a, va), (b, vb) = points[i-1], points[i]
    return va + (vb - va) * (x - a) / (b - a)

runs = []
for l in open(os.path.join(D, 'runs.txt')):
    m = re.match(r'(\S+) exit=(\d+) coupon=(\d+) app_cpu=(\d+) k6_cpu=(\d+) created=(.+)', l.strip())
    if m and not m.group(1).startswith('warm-'): runs.append((m.group(1), int(m.group(3)), datetime.datetime.strptime(m.group(6), '%Y-%m-%d %H:%M:%S.%f').timestamp(), m.group(4), m.group(5)))

rows = collections.OrderedDict()
for name, cid, t0, app_cpu, k6_cpu in runs:
    cfg, rate, rnd = re.match(r'(.+)-(\d+)-(r\d)$', name).groups(); rate = int(rate)
    log = open(os.path.join(D, name + '.log')).read()
    found = re.search(r'msg="lab result \(GET [^)]*\)\\n(.*?)" source=console', log, re.S)
    if not found:  # k6 가 결과 API 까지 가지 못한 측정(준비 단계 실패 등)은 건너뛴다
        print(f'<!-- {name}: 결과 없음, 건너뜀 -->')
        continue
    d = json.loads(found.group(1).encode().decode('unicode_escape'))
    t = re.search(r'발급 완료 처리량=([\d.]+) req/s \(확정까지 ([\d.]+)s, 마지막 응답 시점 미완료 (\d+), 남은 미완료 (\d+)\)', log)
    k = json.load(open(os.path.join(D, name + '.json')))['metrics']
    sent, dropped = k['iterations']['count'], k.get('dropped_iterations', {}).get('count', 0)
    p95 = k['coupon_issue_http_duration']['p(95)']
    samples = [l.split() for l in open(os.path.join(D, name + '.tps'))]
    issued = [(float(s[0]), int(s[2])) for s in samples if int(s[1]) == cid]
    commits = [(float(s[0]), int(s[3])) for s in samples]
    a, b = t0 + W0, t0 + W1
    steady = (interp(issued, b) - interp(issued, a)) / (W1 - W0)
    per_sec = [(issued[i+1][1] - issued[i][1]) / (issued[i+1][0] - issued[i][0]) for i in range(len(issued) - 1) if a <= issued[i][0] and issued[i+1][0] <= b]
    commit_rate = (interp(commits, b) - interp(commits, a)) / (W1 - W0)
    cpu = [tuple(map(float, l.split())) for l in open(os.path.join(D, name + '.cpu'))]
    cpu_avg = (interp(cpu, b) - interp(cpu, a)) / 1e9 / (W1 - W0) * 100
    backlog = int(t.group(3))
    fails = []
    if steady < 0.99 * rate: fails.append('발급 TPS < 99%')
    if dropped > 0: fails.append('못 보냄')
    if backlog > 0.01 * sent: fails.append('미완료 > 1%')
    if cfg in SYNC and p95 > 500: fails.append('p95 > 500ms')
    # write-behind 는 대사 전까지 issued_count 를 갱신하지 않으므로 그 검사는 빼고 본다(대사 뒤 결과는 따로 확인)
    checks = {k: v for k, v in d['checks'].items() if not (cfg in ('wb', 'wb10') and k == 'issuedCountMatchesRows')}
    ok = all(v in (True, None) for v in checks.values()) and (cfg == 'pessimistic' or (d['redisOnlyMembers'] == 0 and d['dbOnlyMembers'] == 0))
    rows.setdefault(cfg, []).append(dict(rate=rate, rnd=rnd, sent=sent, dropped=dropped, steady=steady, smin=min(per_sec) if per_sec else 0,
        smax=max(per_sec) if per_sec else 0, commit=commit_rate, p95=p95, backlog=backlog, confirm=float(t.group(2)), done=float(t.group(1)),
        cpu=cpu_avg, host=f'{app_cpu}% / {k6_cpu}%', fails=fails, ok=ok, issued=d['issueRows']))

summary = []
for cfg, rs in rows.items():
    print(f'\n### {LABEL[cfg]}\n')
    print('| 목표 req/s | 회차 | 보낸 요청 (못 보낸) | 정상 구간 발급 TPS (1초 최소~최대) | MySQL 커밋/초 | HTTP p95 ms | 보내기 끝났을 때 미완료 | 발급 / 확정까지 s | MySQL CPU | app / k6 인스턴스 CPU | 조건 | 정합성 |')
    print('|---:|---|---|---|---:|---:|---:|---|---:|---|---|---|')
    for r in sorted(rs, key=lambda x: (x['rate'], x['rnd'])):
        print(f"| {r['rate']} | {r['rnd']} | {r['sent']} ({r['dropped']}) | {r['steady']:.0f} ({r['smin']:.0f}~{r['smax']:.0f}) | {r['commit']:.0f} | {r['p95']:.0f} | {r['backlog']} | "
              f"{r['issued']} / {r['confirm']:.1f} | {r['cpu']:.0f}% | {r['host']} | {'충족' if not r['fails'] else ', '.join(r['fails'])} | {'지켜짐' if r['ok'] else '확인 필요'} |")
    by_rate = collections.defaultdict(list)
    for r in rs: by_rate[r['rate']].append(r)
    passing = [rate for rate, v in sorted(by_rate.items()) if len(v) == 2 and all(not x['fails'] for x in v)]
    best = max(passing) if passing else None
    nxt = min((rate for rate in by_rate if best is None or rate > best), default=None)
    why = sorted({f for x in by_rate.get(nxt, []) for f in x['fails']}) if nxt else []
    summary.append((LABEL[cfg], best, nxt, why, [round(x['steady']) for x in by_rate.get(nxt, [])]))

print('\n### 따라간 최대 속도 (두 회차 모두 조건을 만족한 가장 높은 목표 속도)\n')
print('| 방식 | 따라간 최대 속도 | 다음 속도 | 다음 속도에서 깨진 조건 | 다음 속도의 정상 구간 발급 TPS |')
print('|---|---:|---:|---|---|')
for label, best, nxt, why, steady in summary:
    print(f"| {label} | {best if best else '-'} | {nxt if nxt else '-'} | {', '.join(why) if why else '-'} | {steady if steady else '-'} |")
