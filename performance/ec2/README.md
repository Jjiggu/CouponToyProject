# EC2 에서 측정하기

Mac 에서는 k6·앱·MySQL·Redis 가 한 기계의 CPU 를 나눠 쓰고 다른 프로그램도 함께 돌아, 50 req/s 차이를 가를 수 없었다([Mac 측정](../../docs/learning/results/2026-10-20-tps-mac.md)). 세 역할을 인스턴스로 나눠 같은 측정을 다시 한다. 결과와 해석: [08. AWS(EC2)에서 잰 쿠폰 1장 발급 확정 한계](../../docs/learning/08-load-test-ec2.md)

## 구성

서울 리전(ap-northeast-2), **세 대 모두 같은 가용 영역(같은 서브넷)**, Ubuntu Server 24.04 LTS (**64비트 Arm**).

| 이름 | 유형 | 디스크 | 하는 일 |
|---|---|---|---|
| db | c7g.large (2 vCPU, 4GiB) | gp3 20GiB | 저장소의 `docker-compose.yml` 에서 `mysql`·`redis` 만 띄운다. Mac 과 같이 각 1 CPU / 1GB 제한. 표본 기록(`sampler.py`) |
| app | c7g.xlarge (4 vCPU, 8GiB) | gp3 기본(8GiB) | 발행자(18080)와 Pub/Sub 구독자·Stream 소비자(18081) 프로세스 |
| k6 | c7g.large (2 vCPU, 4GiB) | gp3 기본(8GiB) | 부하 발생 |

고른 이유 (비용을 줄이되 측정 도중 조건이 바뀌지 않게)
- CPU 크레딧이 있는 t 계열은 뺐다. 크레딧이 떨어지면 측정 도중 성능이 바뀌고, 막지 않으면(unlimited) 추가 요금이 붙는다
- Graviton(c7g)은 같은 vCPU 수의 Intel 계열보다 싸고, vCPU 하나가 물리 코어 하나다. Mac 과 같은 Arm 이라 `cpus: 1.0` 이 "코어 하나" 로 Mac 과 가깝다
- Mac 에서 초당 600건을 보내는 동안 잰 CPU (2026-10-20, 보내는 30초 기준 어림): 발행자 + 구독자·소비자 합쳐 약 1.1코어, k6 약 0.3코어(메모리 약 106MB), MySQL 최대 0.6코어. app 은 JVM 두 개가 먼저 한계가 되지 않도록 4 vCPU, k6 은 2 vCPU, db 는 컨테이너 둘(각 1 CPU 제한)에 2 vCPU
- 측정마다 app·k6 인스턴스의 CPU 사용률을 함께 기록한다(`report.md` 의 "app / k6 인스턴스 CPU"). 80% 를 넘으면 그 측정은 DB 쪽 한계가 아니라 부하 발생기나 앱의 한계일 수 있다

Mac 과 달라지는 점 (결과를 비교할 때 함께 적는다)
- MySQL 데이터는 EBS(네트워크 디스크)에 있다. 이번 한계였던 커밋 때 디스크 동기화 시간이 달라진다
- 앱 ↔ DB·Redis 사이에 실제 네트워크 왕복이 생긴다. 락을 쥔 채 기다리는 시간에 이 왕복이 더해진다

## 보안 그룹 (실험 API 는 인증이 없고 데이터 초기화가 있다. 인터넷에 열지 않는다)

보안 그룹 하나(`coupon-lab`)를 만들어 세 대에 모두 붙인다.

| 방향 | 포트 | 출처 |
|---|---|---|
| 인바운드 | 22 (SSH) | 내 IP |
| 인바운드 | 모든 TCP | 이 보안 그룹 자신 (k6 → app 18080, app → db 3306·6379) |

18080·3306·6379 를 `0.0.0.0/0` 으로 열지 않는다. 측정은 Mac 에서 SSH 로 각 인스턴스에 명령을 보내 진행한다.

## 준비

1. 키 페어를 만들어 `~/.ssh/coupon-lab.pem` 으로 저장하고 `chmod 400 ~/.ssh/coupon-lab.pem`
2. 위 표대로 인스턴스 세 대를 만든다(보안 그룹 `coupon-lab`, 같은 서브넷)
3. `build/ec2/hosts.env` 를 만든다 (`build/` 는 git 에 올라가지 않는다)

```bash
KEY=~/.ssh/coupon-lab.pem
DB_PUB=<db 퍼블릭 IP>    DB_PRIV=<db 프라이빗 IP>
APP_PUB=<app 퍼블릭 IP>  APP_PRIV=<app 프라이빗 IP>
K6_PUB=<k6 퍼블릭 IP>
DB_PASSWORD=<영문·숫자만, 예: openssl rand -hex 16 의 결과>
```

4. 설치와 배포 (코드를 바꿨으면 다시 실행하면 jar 와 k6 스크립트만 새로 올라간다)

```bash
performance/ec2/ec2.sh setup
```

## 측정

```bash
# 먼저 한계 근처를 넓게 본다 (동기 Lua 1회)
CONFIGS=lua ROUNDS=1 RATES="200 300 400 500 600 700" performance/ec2/ec2.sh tps probe

# 한계 근처를 50 단위로, 다섯 방식 2회 (Mac 측정과 같은 순서·조건)
RATES="..." PESSIMISTIC_RATES="..." performance/ec2/ec2.sh tps tps
```

결과는 `build/ec2/<이름>/` 에 모이고 `report.md` 로 요약된다. 조건과 판정은 Mac 측정과 같다(설정마다 앱을 새로 띄우고 데이터를 비운 뒤 초당 200건 30초 예열을 버리고, 각 속도 30초). Mac 과 다른 점은 표본용 MySQL 조회를 컨테이너 밖(DB 인스턴스의 `mysql` 클라이언트)에서 한다는 것이다.

## 비용과 정리

- 세 대 합쳐 시간당 약 0.29달러(us-east-1 온디맨드 기준 c7g.large 0.0725 × 2 + c7g.xlarge 0.145, 서울은 조금 더 비싸다) + 퍼블릭 IPv4 대당 시간당 0.005달러 + 디스크. 준비와 한 번의 전체 측정에 2시간 남짓 걸린다
- 인스턴스는 켜져 있는 동안 시간 단위로 요금이 나간다. 측정하지 않을 때는 **중지**하고(디스크 요금만 남는다), 다시 켜면 퍼블릭 IP 가 바뀌므로 `hosts.env` 를 고친다
- 끝나면 인스턴스 **종료**(디스크도 함께 삭제되는지 확인), 보안 그룹·키 페어 삭제
