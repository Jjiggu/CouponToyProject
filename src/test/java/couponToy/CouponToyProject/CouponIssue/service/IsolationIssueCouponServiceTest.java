package couponToy.CouponToyProject.CouponIssue.service;

import com.zaxxer.hikari.HikariDataSource;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.CouponIssue.support.AbstractCouponIssueIntegrationTestSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
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
 * 1단계: 명시적 락 없이 REPEATABLE READ 와 SERIALIZABLE 비교.
 * 실행 결과는 build/lab/stage1-isolation.md 에 쌓인다.
 */
@SpringBootTest
class IsolationIssueCouponServiceTest extends AbstractCouponIssueIntegrationTestSupport {

    private static final Path REPORT = Path.of("build/lab/stage1-isolation.md");

    // 락을 기다리는 트랜잭션과 그 트랜잭션에 실제로 적용된 격리 수준
    private static final String LOCK_WAITS_SQL = """
            SELECT DISTINCT t.trx_id, t.trx_isolation_level AS isolation, w.waiting_lock_mode AS lock_mode,
                   w.locked_table_name AS locked_table, w.locked_index, LEFT(w.waiting_query, 40) AS waiting_query
            FROM sys.innodb_lock_waits w
            JOIN information_schema.innodb_trx t ON t.trx_id = w.waiting_trx_id
            """;

    @Autowired
    private IsolationIssueCouponService isolationIssueCouponService;

    @Autowired
    private LabService labService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createReport() throws IOException {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, "# 1단계 실행 기록\n\n");
    }

    @Test
    @DisplayName("트랜잭션은 프록시를 통해 시작된다")
    void serviceIsProxied() {
        assertThat(AopUtils.isAopProxy(isolationIssueCouponService)).isTrue();
    }

    // ---------------------------------------------------------------
    // 1. 실행 순서 고정: 두 트랜잭션이 모두 쿠폰을 읽은 뒤에야 쓰기를 시작한다.
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}, 재고 {1}, 서로 다른 회원 2명")
    @CsvSource({"REPEATABLE_READ, 1", "REPEATABLE_READ, 10", "SERIALIZABLE, 1", "SERIALIZABLE, 10"})
    @DisplayName("두 트랜잭션이 같은 issuedCount 를 읽은 뒤 각각 쓴다")
    void bothReadBeforeEitherWrites(CouponIssueStrategy method, int totalCount) throws Exception {
        Long couponId = labCoupon(totalCount);
        List<Long> members = List.of(createMember(1).getMemberId(), createMember(2).getMemberId());

        Interleaving run = runWhileCouponRowLocked(method, couponId, members);
        run.report(method + ", 재고 " + totalCount + ", 서로 다른 회원 2명");

        assertThat(run.lockWaits()).extracting(w -> w.get("isolation"))
                .containsOnly(method == CouponIssueStrategy.SERIALIZABLE ? "SERIALIZABLE" : "REPEATABLE READ");
        assertThat(run.lockWaits()).extracting(w -> w.get("locked_table")).containsOnly("coupons");

        if (method == CouponIssueStrategy.REPEATABLE_READ) {
            // 일반 SELECT 는 막히지 않고, 커밋 직전 UPDATE coupons 에서 X 락을 기다린다.
            assertThat(run.lockWaits()).extracting(w -> w.get("lock_mode")).containsOnly("X,REC_NOT_GAP");
            // 둘 다 issuedCount=0 을 보고 1로 덮어쓴다: 갱신 손실. 재고 1이면 초과 발급까지 생긴다.
            assertThat(run.outcomes()).containsExactly(IssueOutcome.SUCCESS, IssueOutcome.SUCCESS);
            assertThat(run.issueRows()).isEqualTo(2);
            assertThat(run.issuedCount()).isEqualTo(1);
        } else {
            // SERIALIZABLE 은 일반 SELECT 를 공유 락(S) 읽기로 바꾼다. lock-free 가 아니다.
            assertThat(run.lockWaits()).extracting(w -> w.get("lock_mode")).containsOnly("S,REC_NOT_GAP");
            // 둘 다 S 락을 쥔 채 X 락이 필요해지므로 데드락. 하나만 커밋된다. 재고가 남아 있어도 마찬가지다.
            assertThat(run.outcomes()).containsExactlyInAnyOrder(IssueOutcome.SUCCESS, IssueOutcome.DEADLOCK);
            assertThat(run.issueRows()).isEqualTo(1);
            assertThat(run.issuedCount()).isEqualTo(1);
            assertThat(run.deadlockReport()).contains("WE ROLL BACK TRANSACTION");
            // 데드락은 PessimisticLockingFailureException 계열로 올라와 HTTP 503 으로 매핑된다.
            assertThat(run.failures()).allMatch(e -> e instanceof PessimisticLockingFailureException);
        }
    }

    @ParameterizedTest(name = "{0}, 같은 회원 2번")
    @EnumSource(value = CouponIssueStrategy.class, names = {"REPEATABLE_READ", "SERIALIZABLE"})
    @DisplayName("같은 회원의 요청 두 개가 둘 다 중복 확인을 통과한 뒤 저장한다")
    void sameMemberTwiceAtOnce(CouponIssueStrategy method) throws Exception {
        Long couponId = labCoupon(10);
        Long memberId = createMember(1).getMemberId();

        Interleaving run = runWhileCouponRowLocked(method, couponId, List.of(memberId, memberId));
        run.report(method + ", 재고 10, 같은 회원 2번");

        assertThat(run.issueRows()).isEqualTo(1);
        assertThat(run.issuedCount()).isEqualTo(1);
        if (method == CouponIssueStrategy.REPEATABLE_READ) {
            // existsBy... 확인은 둘 다 통과했다. 막은 것은 coupon_issues 의 UNIQUE 제약이다.
            assertThat(run.outcomes()).containsExactlyInAnyOrder(IssueOutcome.SUCCESS, IssueOutcome.UNIQUE_VIOLATION);
        } else {
            assertThat(run.outcomes()).containsExactlyInAnyOrder(IssueOutcome.SUCCESS, IssueOutcome.DEADLOCK);
        }
    }

    // ---------------------------------------------------------------
    // 2. 동시 요청: 시작 래치로 한꺼번에 출발시키고 결과만 기록한다.
    //    RR 의 이상 현상은 실행마다 크기가 다르므로 위 1번 테스트로 증명하고, 여기서는 관찰값을 남긴다.
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
            "REPEATABLE_READ, 재고보다 많은 요청, 10, 50, 1",
            "SERIALIZABLE,    재고보다 많은 요청, 10, 50, 1",
            "REPEATABLE_READ, 재고 충분,         50, 50, 1",
            "SERIALIZABLE,    재고 충분,         50, 50, 1",
            "REPEATABLE_READ, 동일 회원 중복,     50, 10, 5",
            "SERIALIZABLE,    동일 회원 중복,     50, 10, 5",
    })
    @DisplayName("동시 요청 결과 기록")
    void concurrentRequests(CouponIssueStrategy method, String scenario, int totalCount,
                            int memberCount, int requestsPerMember) throws Exception {
        for (int round = 1; round <= 3; round++) {
            LabResult result = burst(method, totalCount, memberCount, requestsPerMember, round);
            reportBurst(method, scenario, round, memberCount * requestsPerMember, result);

            assertThat(result.requests().values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(memberCount * requestsPerMember);
            assertThat(result.requests()).doesNotContainKey(IssueOutcome.ERROR);
            assertThat(result.checks().atMostOncePerMember()).isTrue(); // UNIQUE 제약이 보장
            assertThat(result.issuedCount()).isLessThanOrEqualTo((int) result.issueRows()); // 갱신 손실은 값을 줄이기만 한다
            if (method == CouponIssueStrategy.SERIALIZABLE) {
                assertThat(result.checks().notOverIssued()).isTrue();
                assertThat(result.checks().issuedCountMatchesRows()).isTrue();
            }
        }
    }

    // ---------------------------------------------------------------

    private Long labCoupon(int totalCount) {
        return labService.createCoupon(new CouponCreateRequest("stage1", totalCount)).getCouponId();
    }

    private record Interleaving(List<Map<String, Object>> lockWaits, List<IssueOutcome> outcomes,
                                List<RuntimeException> failures, long issueRows, int issuedCount,
                                String deadlockReport) {

        void report(String title) {
            StringBuilder text = new StringBuilder("## 순서 고정: " + title + "\n\n");
            text.append("- 결과: ").append(outcomes).append('\n');
            text.append("- 예외 타입: ").append(failures.stream().map(e -> e.getClass().getSimpleName()).toList()).append('\n');
            text.append("- coupon_issues 행: ").append(issueRows).append(", coupons.issued_count: ").append(issuedCount).append('\n');
            text.append("- 쿠폰 행 잠금 해제 직전 락 대기:\n");
            lockWaits.forEach(w -> text.append("  - ").append(w).append('\n'));
            if (!deadlockReport.isEmpty()) {
                text.append("\n```text\n").append(deadlockReport).append("\n```\n");
            }
            append(text.append('\n').toString());
        }
    }

    /**
     * 실험 대상이 아니라 실행 순서를 고정하기 위한 장치다.
     * 별도 커넥션이 쿠폰 행을 FOR UPDATE 로 잡고 있는 동안 요청들을 출발시키고,
     * 모든 요청이 락 대기에 들어간 것을 innodb_trx 로 확인한 뒤 놓아준다.
     * - RR: 일반 SELECT 는 이 락에 막히지 않으므로 둘 다 읽고 INSERT 까지 한 뒤 UPDATE 에서 멈춘다.
     * - SERIALIZABLE: 쿠폰 SELECT 가 S 락을 요구하므로 읽기에서 멈춘다. 놓아주면 둘 다 S 락을 얻는다.
     */
    private Interleaving runWhileCouponRowLocked(CouponIssueStrategy method, Long couponId, List<Long> memberIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(memberIds.size());
        List<RuntimeException> failures = Collections.synchronizedList(new ArrayList<>());
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT coupon_id FROM coupons WHERE coupon_id = ? FOR UPDATE")) {
                lock.setLong(1, couponId);
                lock.executeQuery();
            }

            List<Future<IssueOutcome>> futures = memberIds.stream()
                    .map(memberId -> executor.submit(() -> {
                        try {
                            labService.issue(couponId, memberId, method);
                            return IssueOutcome.SUCCESS;
                        } catch (RuntimeException e) {
                            failures.add(e);
                            return IssueOutcome.of(e);
                        }
                    }))
                    .toList();

            List<Map<String, Object>> lockWaits = awaitLockWaits(memberIds.size());
            blocker.rollback();

            List<IssueOutcome> outcomes = new ArrayList<>();
            for (Future<IssueOutcome> future : futures) {
                outcomes.add(future.get(30, SECONDS));
            }
            return new Interleaving(lockWaits, outcomes, List.copyOf(failures),
                    issueCouponRepository.countByCouponId(couponId),
                    couponRepository.findById(couponId).orElseThrow().getIssuedCount(),
                    outcomes.contains(IssueOutcome.DEADLOCK) ? latestDeadlock() : "");
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Map<String, Object>> awaitLockWaits(int expected) throws InterruptedException {
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

    private String latestDeadlock() {
        String status = jdbcTemplate.queryForObject("SHOW ENGINE INNODB STATUS", (rs, rowNum) -> rs.getString("Status"));
        int start = status.indexOf("LATEST DETECTED DEADLOCK");
        int end = status.indexOf("------------\nTRANSACTIONS", start);
        return start < 0 ? "" : status.substring(start, end < 0 ? status.length() : end).strip();
    }

    private LabResult burst(CouponIssueStrategy method, int totalCount, int memberCount, int requestsPerMember, long seed)
            throws Exception {
        Long couponId = labCoupon(totalCount);
        LabService.MemberRange members = labService.createMembers(memberCount);

        List<Long> requests = new ArrayList<>();
        LongStream.rangeClosed(members.firstMemberId(), members.lastMemberId())
                .forEach(memberId -> Collections.nCopies(requestsPerMember, memberId).forEach(requests::add));
        Collections.shuffle(requests, new Random(seed));

        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = requests.stream()
                    .map(memberId -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            labService.issue(couponId, memberId, method);
                        } catch (RuntimeException ignored) {
                            // 결과는 LabService 가 진입점에서 기록한다.
                        }
                        return null;
                    }))
                    .toList();
            ready.await();
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(60, SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        return labService.result(couponId);
    }

    private void reportBurst(CouponIssueStrategy method, String scenario, int round, int requestCount, LabResult r) {
        if (round == 1) {
            append("## 동시 요청: " + method + ", " + scenario
                    + " (재고 " + r.totalCount() + ", 요청 " + requestCount + ", 스레드 " + requestCount
                    + ", Hikari 최대 " + ((HikariDataSource) dataSource).getMaximumPoolSize() + ")\n\n"
                    + "| 회차 | 요청 결과 | 발급 행 | issued_count | 초과 발급 | 수량 불일치 | 전량 발급 | 먼저 온 N명 중 당첨 | N명 밖 당첨 | 완료 순서 역전 | p50/p95/p99 ms | 락 대기 | 데드락 |\n"
                    + "|---:|---|---:|---:|---|---|---|---:|---:|---:|---|---:|---:|\n");
        }
        long n = Math.min(r.totalCount(), r.ordering().validRequests());
        append("| %d | %s | %d | %d | %s | %s | %s | %d/%d | %d | %d | %.0f/%.0f/%.0f | %d | %d |%n".formatted(
                round, r.requests(), r.issueRows(), r.issuedCount(),
                r.checks().notOverIssued() ? "아니오" : "예",
                r.checks().issuedCountMatchesRows() ? "아니오" : "예",
                r.checks().fullyIssued() == null ? "-" : (r.checks().fullyIssued() ? "예" : "아니오"),
                r.ordering().firstNValidWon(), n, r.ordering().winnersOutsideFirstN(),
                r.ordering().completedOutOfArrivalOrder(),
                r.latencyMs().p50(), r.latencyMs().p95(), r.latencyMs().p99(),
                r.innodbLockMetricsDelta().get("lock_row_lock_waits"),
                r.innodbLockMetricsDelta().get("lock_deadlocks")));
        if (round == 3) {
            append("\n");
        }
    }

    private static synchronized void append(String text) {
        try {
            Files.writeString(REPORT, text, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
