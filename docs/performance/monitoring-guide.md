# 모니터링 가이드

성능 테스트 중 MySQL, Redis, 애플리케이션 컨테이너 자원 사용량을 시계열로 보기 위한 가이드다.

## 구성

- `Prometheus`
- `Grafana`
- `cAdvisor`
- `mysqld-exporter`
- `redis-exporter`

## 실행

애플리케이션과 모니터링을 같이 올리려면 아래처럼 실행한다.

```bash
docker compose up --build -d
```

모니터링만 추가로 올리고 싶다면:

```bash
docker compose up -d
```

`run-benchmarks.sh` 는 Prometheus 가 떠 있으면 benchmark 종료 후 각 run 구간의 모니터링 지표를 자동으로 추출해 같은 결과 폴더에 저장한다.

## 접속

- Grafana: `http://localhost:3000`
- Prometheus: `http://localhost:9090`

Grafana 기본 계정:

- ID: `admin`
- PW: `admin`

Grafana에는 기본 Prometheus datasource 와 `CouponToy Overview` 대시보드가 자동으로 등록된다.

## 대시보드에서 볼 지표

- Container CPU Usage
- Container Memory Working Set
- MySQL Threads Running
- MySQL Queries / sec
- MySQL Slow Queries / sec
- Redis Commands / sec
- Redis Memory Used

## 권장 확인 순서

1. Grafana `CouponToy Overview` 대시보드 열기
2. `k6` benchmark 실행
3. `500 VU / stock 100`, `500 VU / stock 500` 구간에서
   - MySQL CPU/Threads Running 상승 여부
   - Redis ops/sec 급증 여부
   - app/mysql/redis 메모리 급등 여부
4. run 종료 후 Prometheus/Grafana 타임라인 기준으로 병목 구간 확인

## 자동 추출 결과

benchmark 종료 후 아래 파일이 함께 생성된다.

- `monitoring-summary.csv`
- `monitoring-report.md`

여기에는 run 구간별 평균/최대 지표가 정리된다.

- app/mysql/redis CPU
- app/mysql/redis memory
- MySQL threads running
- MySQL queries per second
- MySQL slow queries per second
- Redis commands per second

## 정리

모니터링까지 종료하려면:

```bash
docker compose down
```
