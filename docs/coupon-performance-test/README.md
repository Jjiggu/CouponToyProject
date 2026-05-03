# 쿠폰 발급 성능/부하 테스트 문서

이 폴더는 쿠폰 발급 API의 성능 차이, 정합성, 병목 원인을 비교하기 위한 테스트 문서 모음입니다.

## 문서 구성

- [01. 테스트 계획](01-test-plan.md): 목적, 비교 대상, 시나리오, 성공 기준
- [02. 실행 절차](02-runbook.md): Docker, seed, token 생성, k6 실행 순서
- [03. 지표와 병목 분석](03-metrics-and-bottleneck.md): Prometheus/Grafana 지표와 병목 판정 기준
- [04. 정합성 검증](04-consistency-check.md): 과발급, 중복 발급, Redis/DB 일치 여부 확인
- [05. 결과 확인 가이드](05-result-check-guide.md): k6, Grafana, Prometheus, DB/Redis 결과 확인 방법
- [06. 결과 리포트 자동 생성](06-result-reporting.md): k6 summary JSON 집계와 Markdown 리포트 생성 방법

## 비교 대상 API

| 전략 | 엔드포인트 | 정상 응답 |
|---|---|---|
| 비관적 락 | `POST /coupons/issue/{couponId}/pessimistic` | `201 Created` |
| Redis Transactional | `POST /coupons/issue/{couponId}/redis-transactional` | `201 Created` |
| Redis + Spring Event | `POST /coupons/issue/{couponId}/redis-event` | `202 Accepted` |

공통 실패 응답은 다음과 같습니다.

| 상황 | HTTP Status |
|---|---:|
| 중복 발급 | `409 Conflict` |
| 쿠폰 품절 | `409 Conflict` |
| Redis 경합 retry 초과 | `503 Service Unavailable` |
| 서버 오류 | `5xx` |

## 관련 실행 자산

- k6 스크립트: `performance/k6/coupon-issue.js`
- JWT token 생성기: `performance/scripts/generate-jwt-tokens.js`
- DB seed/reset SQL: `performance/sql/reset-and-seed.sql`
- 정합성 검증 SQL: `performance/sql/consistency-check.sql`
- Prometheus/Grafana 설정: `monitoring/`
