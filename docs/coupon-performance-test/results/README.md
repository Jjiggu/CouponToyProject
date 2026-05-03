# 성능 테스트 결과 문서

`performance/results/*.json`에 저장된 k6 summary JSON을 집계해 생성된 결과 문서를 보관한다.

생성 명령:

```bash
node performance/scripts/aggregate-k6-results.js
```

기본 출력 파일:

```text
docs/coupon-performance-test/results/latest-summary.md
```
