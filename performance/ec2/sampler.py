# DB 인스턴스에서 돈다. MySQL 컨테이너 누적 CPU(ns)를 0.1초마다, 가장 최근 쿠폰의 발급 행 수와 Com_commit 을 1초마다 기록한다.
# 발급 행 수를 센다(issued_count 가 아니라). 9단계 write-behind 는 대사 전까지 issued_count 를 갱신하지 않는다. 나머지 방식은 둘이 같다(정합성 검증).
# 사용법: python3 sampler.py <출력 접두사> <멈춤 파일>   → <접두사>.cpu, <접두사>.tps  (MySQL 접속 정보는 ~/.my.cnf)
import socket, http.client, json, time, sys, os, subprocess
class UConn(http.client.HTTPConnection):
    def __init__(self): super().__init__('localhost')
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX); self.sock.connect('/var/run/docker.sock')
prefix, stop = sys.argv[1], sys.argv[2]
cpu, tps = open(prefix + '.cpu', 'w'), open(prefix + '.tps', 'w')
sql = "SELECT c.coupon_id, (SELECT COUNT(*) FROM coupon_issues i WHERE i.coupon_id = c.coupon_id) FROM coupons c ORDER BY c.coupon_id DESC LIMIT 1; SHOW GLOBAL STATUS LIKE 'Com_commit';"
next_query = 0
while not os.path.exists(stop):
    c = UConn(); c.request('GET', '/containers/coupon-toy-mysql/stats?stream=false&one-shot=true')
    d = json.loads(c.getresponse().read()); c.close()
    cpu.write(f"{time.time():.3f} {d['cpu_stats']['cpu_usage']['total_usage']}\n"); cpu.flush()
    if time.time() >= next_query:
        t = time.time()
        r = subprocess.run(['mysql', '-N', 'CouponToyProject_lab', '-e', sql], capture_output=True, text=True).stdout.split()
        if len(r) >= 4:
            tps.write(f"{(t + time.time()) / 2:.3f} {r[0]} {r[1]} {r[3]}\n"); tps.flush()
        next_query = t + 1
    time.sleep(0.1)
