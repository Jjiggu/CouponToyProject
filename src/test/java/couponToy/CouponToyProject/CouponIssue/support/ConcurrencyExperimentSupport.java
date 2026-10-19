package couponToy.CouponToyProject.CouponIssue.support;

import com.zaxxer.hikari.HikariDataSource;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.CouponIssue.service.CouponIssueStrategy;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단계별 동시성 실험 공통 장치: 실행 순서를 고정한 두 트랜잭션 재현, 동시 요청, 결과 기록.
 */
public abstract class ConcurrencyExperimentSupport extends AbstractCouponIssueIntegrationTestSupport {

    // 락을 기다리는 트랜잭션과 그 트랜잭션에 실제로 적용된 격리 수준, CATS 스케줄링 가중치(MySQL 8.0.20+)
    private static final String LOCK_WAITS_SQL = """
            SELECT DISTINCT t.trx_id, t.trx_isolation_level AS isolation, t.trx_schedule_weight AS weight, w.waiting_lock_mode AS lock_mode,
                   w.locked_table_name AS locked_table, w.locked_index, LEFT(w.waiting_query, 200) AS waiting_query
            FROM sys.innodb_lock_waits w
            JOIN information_schema.innodb_trx t ON t.trx_id = w.waiting_trx_id
            """;

    @Autowired
    protected LabService labService;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected abstract Path reportPath();

    protected static void createReport(Path report, String title) throws IOException {
        Files.createDirectories(report.getParent());
        Files.writeString(report, "# " + title + "\n\n");
    }

    protected Long labCoupon(int totalCount) {
        return labService.createCoupon(new CouponCreateRequest("stage1", totalCount)).getCouponId();
    }

    protected record Interleaving(List<Map<String, Object>> lockWaits, List<IssueOutcome> outcomes,
                                  List<Long> elapsedAfterReleaseMs, List<RuntimeException> failures,
                                  long issueRows, int issuedCount, String deadlockReport) {

        // 데드락 로그에서 "WAITING FOR THIS LOCK" 아래의 RECORD LOCKS 줄 (기다리던 락)
        public List<String> deadlockWaitingLocks() {
            return deadlockReport.lines().filter(line -> line.startsWith("RECORD LOCKS") && line.endsWith("waiting")).toList();
        }
    }

    private record Finished(IssueOutcome outcome, long finishedAt) {
    }

    /**
     * 실험 대상이 아니라 실행 순서를 고정하기 위한 장치다.
     * 별도 커넥션이 쿠폰 행을 FOR UPDATE 로 잡고 있는 동안 요청들을 출발시키고,
     * 모든 요청이 락 대기에 들어간 것을 innodb_trx 로 확인한 뒤 놓아준다.
     * - RR, RC: 일반 SELECT 는 이 락에 막히지 않으므로 둘 다 읽고 INSERT 까지 한 뒤 UPDATE 에서 멈춘다.
     * - SERIALIZABLE: 쿠폰 SELECT 가 S 락을 요구하므로 읽기에서 멈춘다. 놓아주면 둘 다 S 락을 얻는다.
     */
    protected Interleaving runWhileCouponRowLocked(CouponIssueStrategy method, boolean duplicateCheck,
                                                   Long couponId, List<Long> memberIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(memberIds.size());
        List<RuntimeException> failures = Collections.synchronizedList(new ArrayList<>());
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT coupon_id FROM coupons WHERE coupon_id = ? FOR UPDATE")) {
                lock.setLong(1, couponId);
                lock.executeQuery();
            }

            List<Future<Finished>> futures = memberIds.stream()
                    .map(memberId -> executor.submit(() -> {
                        try {
                            labService.issue(couponId, memberId, method, duplicateCheck);
                            return new Finished(IssueOutcome.SUCCESS, System.nanoTime());
                        } catch (RuntimeException e) {
                            failures.add(e);
                            return new Finished(IssueOutcome.of(e), System.nanoTime());
                        }
                    }))
                    .toList();

            List<Map<String, Object>> lockWaits = awaitLockWaits(memberIds.size());
            blocker.rollback();
            long releasedAt = System.nanoTime();

            List<IssueOutcome> outcomes = new ArrayList<>();
            List<Long> elapsed = new ArrayList<>();
            for (Future<Finished> future : futures) {
                Finished finished = future.get(120, SECONDS);
                outcomes.add(finished.outcome());
                elapsed.add((finished.finishedAt() - releasedAt) / 1_000_000);
            }
            return new Interleaving(lockWaits, outcomes, elapsed, List.copyOf(failures),
                    issueCouponRepository.countByCouponId(couponId),
                    couponRepository.findById(couponId).orElseThrow().getIssuedCount(),
                    outcomes.contains(IssueOutcome.DEADLOCK) ? latestDeadlock() : "");
        } finally {
            executor.shutdownNow();
        }
    }

    protected List<Map<String, Object>> awaitLockWaits(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (true) {
            List<Map<String, Object>> waits = jdbcTemplate.queryForList(LOCK_WAITS_SQL);
            if (waits.size() >= expected) {
                return waits;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("락 대기 " + expected + "건을 기다렸지만 관찰된 것은 " + waits
                        + "\ninnodb_trx=" + jdbcTemplate.queryForList("SELECT trx_id, trx_state, trx_isolation_level, trx_query FROM information_schema.innodb_trx")
                        + "\nprocesslist=" + jdbcTemplate.queryForList("SELECT id, command, state, LEFT(info, 60) AS info FROM information_schema.processlist WHERE db = DATABASE()"));
            }
            // 조건 폴링 간격. innodb_trx 는 캐시라서 마지막 조회 후 0.1초가 지나야 갱신된다. 더 자주 읽으면 계속 옛 값이 보인다.
            Thread.sleep(200);
        }
    }

    protected String latestDeadlock() {
        String status = jdbcTemplate.queryForObject("SHOW ENGINE INNODB STATUS", (rs, rowNum) -> rs.getString("Status"));
        int start = status.indexOf("LATEST DETECTED DEADLOCK");
        int end = status.indexOf("------------\nTRANSACTIONS", start);
        return start < 0 ? "" : status.substring(start, end < 0 ? status.length() : end).strip();
    }

    protected void reportInterleaving(String title, Interleaving run) {
        StringBuilder text = new StringBuilder("## 순서 고정: " + title + "\n\n");
        text.append("- 결과: ").append(run.outcomes()).append('\n');
        text.append("- 쿠폰 행 잠금을 푼 뒤 끝날 때까지: ").append(run.elapsedAfterReleaseMs()).append(" ms\n");
        text.append("- 예외 타입: ").append(run.failures().stream().map(e -> e.getClass().getSimpleName()).toList()).append('\n');
        text.append("- coupon_issues 행: ").append(run.issueRows()).append(", coupons.issued_count: ").append(run.issuedCount()).append('\n');
        text.append("- 쿠폰 행 잠금 해제 직전 락 대기:\n");
        run.lockWaits().forEach(w -> text.append("  - ").append(w).append('\n'));
        if (!run.deadlockReport().isEmpty()) {
            text.append("\n```text\n").append(run.deadlockReport()).append("\n```\n");
        }
        append(text.append('\n').toString());
    }

    protected record Burst(LabResult result, long wallMs) {
    }

    /**
     * 요청 수만큼 스레드를 만들고, 모두 준비된 뒤 시작 래치를 열어 한꺼번에 LabService.issue() 를 호출한다.
     */
    protected Burst burst(CouponIssueStrategy method, boolean duplicateCheck, int totalCount,
                          int memberCount, int requestsPerMember, long seed) throws Exception {
        Long couponId = labCoupon(totalCount);
        LabService.MemberRange members = labService.createMembers(memberCount);

        List<Long> requests = new ArrayList<>();
        LongStream.rangeClosed(members.firstMemberId(), members.lastMemberId())
                .forEach(memberId -> Collections.nCopies(requestsPerMember, memberId).forEach(requests::add));
        Collections.shuffle(requests, new Random(seed));

        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        long startedAt;
        try {
            List<Future<Object>> futures = requests.stream()
                    .map(memberId -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            labService.issue(couponId, memberId, method, duplicateCheck);
                        } catch (RuntimeException ignored) {
                            // 결과는 LabService 가 진입점에서 기록한다.
                        }
                        return null;
                    }))
                    .toList();
            ready.await();
            startedAt = System.nanoTime();
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(300, SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        return new Burst(labService.result(couponId), (System.nanoTime() - startedAt) / 1_000_000);
    }

    protected void reportBurst(String title, int round, int requestCount, Burst burst) {
        LabResult r = burst.result();
        if (round == 1) {
            append("## 동시 요청: " + title
                    + " (재고 " + r.totalCount() + ", 요청 " + requestCount + ", 스레드 " + requestCount
                    + ", Hikari 최대 " + ((HikariDataSource) dataSource).getMaximumPoolSize() + ")\n\n"
                    + "| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 총 소요 ms | 락 대기 | 데드락 | 타임아웃 | 시도 분포 | 버전 충돌 |\n"
                    + "|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|\n");
        }
        long n = Math.min(r.totalCount(), r.ordering().validRequests());
        append("| %d | %s | %d | %d | %s | %s | %s | %d/%d | %d | %d | %.0f/%.0f/%.0f | %d | %d | %d | %d | %s | %d |%n".formatted(
                round, r.requests(), r.issueRows(), r.issuedCount(),
                r.checks().notOverIssued() ? "아니오" : "예",
                r.checks().issuedCountMatchesRows() ? "아니오" : "예",
                r.checks().fullyIssued() == null ? "-" : (r.checks().fullyIssued() ? "예" : "아니오"),
                r.ordering().firstNValidWon(), n, r.ordering().winnersOutsideFirstN(),
                r.ordering().completedOutOfArrivalOrder(),
                r.latencyMs().p50(), r.latencyMs().p95(), r.latencyMs().p99(), burst.wallMs(),
                r.innodbLockMetricsDelta().get("lock_row_lock_waits"),
                r.innodbLockMetricsDelta().get("lock_deadlocks"),
                r.innodbLockMetricsDelta().get("lock_timeouts"),
                r.attempts().histogram(), r.attempts().versionConflicts()));
    }

    protected void append(String text) {
        synchronized (ConcurrencyExperimentSupport.class) {
            try {
                Files.writeString(reportPath(), text, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 실패 지점에 도달한 요청을 멈췄다가 놓아준다. */
    public static final class Pause implements FailurePoints.Action {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public void run() {
            reached.countDown();
            try {
                released.await(10, SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void awaitReached() throws InterruptedException {
            assertThat(reached.await(10, SECONDS)).as("실패 지점 도달").isTrue();
        }

        public boolean reachedWithin(int seconds) throws InterruptedException {
            return reached.await(seconds, SECONDS);
        }

        public void release() {
            released.countDown();
        }
    }
}
