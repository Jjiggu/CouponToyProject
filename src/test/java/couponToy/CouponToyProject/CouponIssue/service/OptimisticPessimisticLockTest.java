package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.model.VersionedCoupon;
import couponToy.CouponToyProject.Coupon.repository.VersionedCouponRepository;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2단계: 같은 발급 조건(회원 → 쿠폰 → 중복 확인 → 증가 → 저장, REPEATABLE READ)에서
 * 비관적 락(SELECT ... FOR UPDATE)과 낙관적 락(@Version, 재시도 없음 / 제한된 재시도)을 비교한다.
 * 실행 결과는 build/lab/stage2-lock.md 에 쌓인다.
 */
@SpringBootTest
class OptimisticPessimisticLockTest extends ConcurrencyExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage2-lock.md");

    @Autowired
    private OptimisticIssueCouponService optimisticIssueCouponService;

    @Autowired
    private VersionedCouponRepository versionedCouponRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${coupon.issue.optimistic.max-attempts:5}")
    private int maxAttempts;

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "2단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    // ---------------------------------------------------------------
    // 1. 실행 순서 고정: 쿠폰 행을 다른 커넥션이 잡은 동안 두 요청을 출발시키고, 둘 다 대기에 들어간 뒤 놓아준다.
    //    대기 락 = 놓아주기 직전 "테이블:락 모드", 대기 쿼리 = 대기 중인 SQL 에 들어 있어야 하는 글자
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}, 같은 회원 {1}, 재고 {2}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식           | 같은회원 | 재고 | 결과                         | 행 | issued_count | 대기 락                                 | 대기 쿼리     | 시도 분포
            PESSIMISTIC      | false    | 1    | SOLD_OUT+SUCCESS             | 1  | 1            | coupons:X,REC_NOT_GAP                   | for update    | 1=2
            PESSIMISTIC      | false    | 10   | SUCCESS+SUCCESS              | 2  | 2            | coupons:X,REC_NOT_GAP                   | for update    | 1=2
            PESSIMISTIC      | true     | 10   | SUCCESS+UNIQUE_VIOLATION     | 1  | 1            | coupons:X,REC_NOT_GAP                   | for update    | 1=2
            OPTIMISTIC       | false    | 1    | OPTIMISTIC_CONFLICT+SUCCESS  | 1  | 1            | coupons:X,REC_NOT_GAP                   | and version=  | 1=2
            OPTIMISTIC       | false    | 10   | OPTIMISTIC_CONFLICT+SUCCESS  | 1  | 1            | coupons:X,REC_NOT_GAP                   | and version=  | 1=2
            OPTIMISTIC       | true     | 10   | SUCCESS+UNIQUE_VIOLATION     | 1  | 1            | coupon_issues:S+coupons:X,REC_NOT_GAP   | and version=  | 1=2
            OPTIMISTIC_RETRY | false    | 1    | SOLD_OUT+SUCCESS             | 1  | 1            | coupons:X,REC_NOT_GAP                   | and version=  | 1=1,2=1
            OPTIMISTIC_RETRY | false    | 10   | SUCCESS+SUCCESS              | 2  | 2            | coupons:X,REC_NOT_GAP                   | and version=  | 1=1,2=1
            OPTIMISTIC_RETRY | true     | 10   | SUCCESS+UNIQUE_VIOLATION     | 1  | 1            | coupon_issues:S+coupons:X,REC_NOT_GAP   | and version=  | 1=2
            """)
    @DisplayName("두 요청이 동시에 출발한다")
    void bothStartWhileCouponRowLocked(CouponIssueStrategy method, boolean sameMember, int totalCount,
                                       String expectedOutcomes, long expectedRows, int expectedIssuedCount,
                                       String expectedWaitLocks, String expectedWaitingSql, String expectedAttempts) throws Exception {
        Long couponId = labCoupon(totalCount);
        Long first = createMember(1).getMemberId();
        Long second = sameMember ? first : createMember(2).getMemberId();

        Interleaving run = runWhileCouponRowLocked(method, true, couponId, List.of(first, second));
        LabResult result = labService.result(couponId);
        reportInterleaving(method + ", 재고 " + totalCount + (sameMember ? ", 같은 회원 2번" : ", 서로 다른 회원 2명")
                + " (시도 분포 " + result.attempts().histogram() + ")", run);

        assertThat(run.lockWaits()).extracting(w -> w.get("isolation")).containsOnly("REPEATABLE READ");
        assertThat(run.lockWaits().stream().map(w -> w.get("locked_table") + ":" + w.get("lock_mode")).distinct().sorted())
                .containsExactly(expectedWaitLocks.split("\\+"));
        assertThat(run.lockWaits()).anyMatch(w -> String.valueOf(w.get("waiting_query")).contains(expectedWaitingSql));

        assertThat(run.outcomes().stream().map(Enum::name).sorted()).containsExactly(sortedSplit(expectedOutcomes));
        assertThat(run.issueRows()).isEqualTo(expectedRows);
        assertThat(run.issuedCount()).isEqualTo(expectedIssuedCount);
        assertThat(histogramText(result)).isEqualTo(expectedAttempts);
    }

    // ---------------------------------------------------------------
    // 2. 버전 검사를 거치지 않는 쓰기: 다른 트랜잭션이 쿠폰 행에 쓴 뒤 커밋하기 전에 낙관적 요청이 읽고,
    //    그 쓰기가 커밋된 뒤에야 낙관적 요청이 커밋 직전 UPDATE ... AND version = ? 를 실행한다.
    //    정상이라면 issued_count = 끼어든 쓰기 1 + 요청 성공 시 1.
    // ---------------------------------------------------------------

    enum Writer {
        VERSIONED_ENTITY,   // VersionedCoupon 으로 증가: version 을 올린다
        BULK_UPDATE,        // CouponRepository.increaseIssuedCount (Redis 방식이 쓰는 JPQL 벌크 UPDATE)
        COUPON_ENTITY       // Coupon 엔티티 dirty checking (1단계, 비관적 락 방식의 쓰기)
    }

    @ParameterizedTest(name = "끼어든 쓰기 {0}, 요청 {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # 끼어든 쓰기     | 방식             | 결과                 | issued_count | version | 발급 행 | 시도 분포
            VERSIONED_ENTITY  | OPTIMISTIC       | OPTIMISTIC_CONFLICT  | 1            | 1       | 0       | 1=1
            VERSIONED_ENTITY  | OPTIMISTIC_RETRY | SUCCESS              | 2            | 2       | 1       | 2=1
            BULK_UPDATE       | OPTIMISTIC       | SUCCESS              | 1            | 1       | 1       | 1=1
            COUPON_ENTITY     | OPTIMISTIC       | SUCCESS              | 1            | 1       | 1       | 1=1
            """)
    @DisplayName("버전 검사는 version 을 올리는 쓰기끼리만 충돌을 잡는다")
    void writerCommitsBetweenReadAndVersionCheck(Writer writer, CouponIssueStrategy method, IssueOutcome expectedOutcome,
                                                 int expectedIssuedCount, long expectedVersion, long expectedRows,
                                                 String expectedAttempts) throws Exception {
        Long couponId = labCoupon(10);
        Long memberId = createMember(1).getMemberId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> writerDone = executor.submit(() -> transaction.executeWithoutResult(status -> {
                write(writer, couponId);
                written.countDown();
                awaitQuietly(release);
            }));
            assertThat(written.await(10, SECONDS)).isTrue();

            Future<IssueOutcome> request = executor.submit(() -> {
                try {
                    labService.issue(couponId, memberId, method);
                    return IssueOutcome.SUCCESS;
                } catch (RuntimeException e) {
                    return IssueOutcome.of(e);
                }
            });
            // 요청은 락 없이 읽고 INSERT 까지 마친 뒤, 커밋 직전 UPDATE 에서 끼어든 쓰기의 X 락을 기다린다.
            List<Map<String, Object>> waits = awaitLockWaits(1);
            release.countDown();
            writerDone.get(30, SECONDS);
            IssueOutcome outcome = request.get(30, SECONDS);

            Map<String, Object> coupon = jdbcTemplate.queryForMap("SELECT issued_count, version FROM coupons WHERE coupon_id = ?", couponId);
            long rows = issueCouponRepository.countByCouponId(couponId);
            String attempts = histogramText(labService.result(couponId));
            int correctIssuedCount = 1 + (outcome == IssueOutcome.SUCCESS ? 1 : 0);
            append("## 끼어든 쓰기: " + writer + ", 요청: " + method + "\n\n"
                    + "- 요청이 기다린 SQL: " + waits.get(0).get("waiting_query") + "\n"
                    + "- 결과: " + outcome + ", 시도 분포 " + attempts + "\n"
                    + "- 최종 issued_count: " + coupon.get("issued_count") + " (정상이라면 " + correctIssuedCount + "), version: "
                    + coupon.get("version") + ", coupon_issues 행: " + rows + "\n\n");

            assertThat(String.valueOf(waits.get(0).get("waiting_query"))).contains("and version=");
            assertThat(outcome).isEqualTo(expectedOutcome);
            assertThat(((Number) coupon.get("issued_count")).intValue()).isEqualTo(expectedIssuedCount);
            assertThat(((Number) coupon.get("version")).longValue()).isEqualTo(expectedVersion);
            assertThat(rows).isEqualTo(expectedRows);
            assertThat(attempts).isEqualTo(expectedAttempts);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void write(Writer writer, Long couponId) {
        switch (writer) {
            case VERSIONED_ENTITY -> {
                VersionedCoupon coupon = versionedCouponRepository.findById(couponId).orElseThrow();
                coupon.increaseIssueAmount();
                versionedCouponRepository.flush();
            }
            case BULK_UPDATE -> couponRepository.increaseIssuedCount(couponId);
            case COUPON_ENTITY -> {
                Coupon coupon = couponRepository.findById(couponId).orElseThrow();
                coupon.increaseIssueAmount();
                couponRepository.flush();
            }
        }
    }

    // ---------------------------------------------------------------
    // 3. 락을 넘겨주는 순서 (MySQL 8.0.20+ 은 CATS 만 쓴다): 같은 행을 기다리는 트랜잭션 중
    //    더 많은 트랜잭션을 막고 있는 쪽(가중치가 큰 쪽)이 먼저 받는다. 가중치가 같으면 오래 기다린 쪽이 먼저다.
    // ---------------------------------------------------------------

    // 대기 여부와 락 소유자는 performance_schema 로 본다. innodb_trx 는 0.1초 캐시라서,
    // 직전 케이스가 끝나고 0.1초 안에 조회하면 직전 케이스의 대기 행이 보인다(커넥션도 풀에서 재사용된다).
    private static final String COUPON_LOCK_WAITERS_SQL = """
            SELECT DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID AS trx_id
            FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
            WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'coupons'
            """;
    private static final String MEMBER_ROW_LOCK_OWNER_SQL = """
            SELECT DISTINCT ENGINE_TRANSACTION_ID AS trx_id
            FROM performance_schema.data_locks
            WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = 'members' AND LOCK_TYPE = 'RECORD' AND LOCK_STATUS = 'GRANTED'
            """;
    private static final String WAITER_WEIGHTS_SQL =
            "SELECT trx_id, trx_schedule_weight AS weight FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'";

    @ParameterizedTest(name = "늦게 온 요청이 쥔 회원 행을 기다리는 트랜잭션 {0}개")
    @CsvSource({"0, SUCCESS, SOLD_OUT", "3, SOLD_OUT, SUCCESS"})
    @DisplayName("비관적 락: 쿠폰 행 X 락은 먼저 기다린 순서가 아니라 CATS 가중치 순서로 넘어간다")
    void catsGrantsCouponLockByWeight(int waitersOnLater, IssueOutcome expectedEarlier, IssueOutcome expectedLater) throws Exception {
        Long couponId = labCoupon(1);
        Long earlierMember = createMember(1).getMemberId();
        Long laterMember = createMember(2).getMemberId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2 + waitersOnLater);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT coupon_id FROM coupons WHERE coupon_id = ? FOR UPDATE")) {
                lock.setLong(1, couponId);
                lock.executeQuery();
            }

            // 먼저 온 요청: 다른 락 없이 쿠폰 행만 기다린다.
            Future<IssueOutcome> earlier = executor.submit(() -> classify(() -> labService.issue(couponId, earlierMember, CouponIssueStrategy.PESSIMISTIC)));
            awaitCouponLockWaiters(1);

            // 늦게 온 요청: 회원 행을 먼저 잠근 트랜잭션 안에서 쿠폰 발급에 들어온다 (다른 기능과 한 트랜잭션으로 묶인 발급).
            Future<IssueOutcome> later = executor.submit(() -> classify(() -> transaction.execute(status -> {
                jdbcTemplate.queryForList("SELECT member_id FROM members WHERE member_id = ? FOR UPDATE", laterMember);
                return labService.issue(couponId, laterMember, CouponIssueStrategy.PESSIMISTIC);
            })));
            List<String> couponWaiters = awaitCouponLockWaiters(2);

            // 늦게 온 요청이 쥔 회원 행을 기다리는 트랜잭션들. 늦게 온 요청이 이들을 막고 있으므로 가중치가 올라간다.
            List<Future<?>> memberWaiters = new ArrayList<>();
            for (int i = 0; i < waitersOnLater; i++) {
                memberWaiters.add(executor.submit(() -> {
                    try (Connection connection = dataSource.getConnection()) {
                        connection.setAutoCommit(false);
                        try (PreparedStatement lock = connection.prepareStatement("SELECT member_id FROM members WHERE member_id = ? FOR UPDATE")) {
                            lock.setLong(1, laterMember);
                            lock.executeQuery();
                        }
                        connection.rollback();
                    }
                    return null;
                }));
            }
            List<Map<String, Object>> waits = awaitLockWaits(2 + waitersOnLater);
            // 늦게 온 요청 = 회원 행 락을 쥔 트랜잭션, 먼저 온 요청 = 나머지 쿠폰 행 대기자
            String laterTrx = String.valueOf(jdbcTemplate.queryForObject(MEMBER_ROW_LOCK_OWNER_SQL, Object.class));
            String earlierTrx = couponWaiters.stream().filter(trx -> !trx.equals(laterTrx)).findFirst().orElseThrow();
            long[] weights = awaitCouponLockWeights(earlierTrx, laterTrx, waitersOnLater > 0); // {먼저 온 요청, 늦게 온 요청}
            blocker.rollback();

            IssueOutcome earlierOutcome = earlier.get(30, SECONDS);
            IssueOutcome laterOutcome = later.get(30, SECONDS);
            for (Future<?> waiter : memberWaiters) {
                waiter.get(30, SECONDS);
            }

            StringBuilder text = new StringBuilder("## CATS: 늦게 온 요청이 쥔 회원 행을 기다리는 트랜잭션 " + waitersOnLater + "개, 재고 1\n\n");
            text.append("- 쿠폰 행 가중치: 먼저 온 요청 ").append(weights[0]).append(", 늦게 온 요청 ").append(weights[1])
                    .append(" (trx ").append(earlierTrx).append(" / ").append(laterTrx).append(", 늦게 온 요청 = 회원 행 락 소유자)\n");
            text.append("- 결과: 먼저 온 요청 ").append(earlierOutcome).append(", 늦게 온 요청 ").append(laterOutcome).append('\n');
            text.append("- 쿠폰 행 잠금 해제 직전 락 대기:\n");
            waits.forEach(w -> text.append("  - ").append(w).append('\n'));
            append(text.append('\n').toString());

            if (waitersOnLater == 0) {
                assertThat(weights[1]).isEqualTo(weights[0]);
            } else {
                assertThat(weights[1]).isGreaterThan(weights[0]);
            }
            assertThat(earlierOutcome).isEqualTo(expectedEarlier);
            assertThat(laterOutcome).isEqualTo(expectedLater);
            assertThat(issueCouponRepository.countByCouponId(couponId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private interface Issue {
        Object run();
    }

    private static IssueOutcome classify(Issue issue) {
        try {
            issue.run();
            return IssueOutcome.SUCCESS;
        } catch (RuntimeException e) {
            return IssueOutcome.of(e);
        }
    }

    private List<String> awaitCouponLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (true) {
            List<String> waiters = jdbcTemplate.queryForList(COUPON_LOCK_WAITERS_SQL, Object.class).stream()
                    .map(String::valueOf).toList();
            if (waiters.size() >= expected) {
                return waiters;
            }
            assertThat(System.nanoTime()).as("쿠폰 행 대기 %d건을 기다림, 관찰: %s", expected, waiters).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    // 가중치는 InnoDB 가 대기 그래프를 주기적으로 분석해 갱신한다(innodb_trx 로만 보인다). 기대한 관계가 보일 때까지 기다린 뒤 잠금을 푼다.
    private long[] awaitCouponLockWeights(String earlierTrx, String laterTrx, boolean expectLaterHeavier) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(15);
        while (true) {
            Map<String, Long> weights = new java.util.HashMap<>();
            for (Map<String, Object> row : jdbcTemplate.queryForList(WAITER_WEIGHTS_SQL)) {
                Object weight = row.get("weight");
                weights.put(String.valueOf(row.get("trx_id")), weight == null ? null : ((Number) weight).longValue());
            }
            Long earlierWeight = weights.get(earlierTrx);
            Long laterWeight = weights.get(laterTrx);
            if (earlierWeight != null && laterWeight != null && (!expectLaterHeavier || laterWeight > earlierWeight)) {
                return new long[]{earlierWeight, laterWeight};
            }
            assertThat(System.nanoTime()).as("가중치를 기다림. 먼저 온 요청 %s, 늦게 온 요청 %s, 관찰: %s", earlierTrx, laterTrx, weights)
                    .isLessThan(deadline);
            Thread.sleep(200); // innodb_trx 캐시(0.1초)보다 길게
        }
    }

    @Test
    @DisplayName("재시도를 바깥 트랜잭션 안에서 부르면 막는다 (모든 시도가 같은 영속성 컨텍스트를 쓰게 되므로)")
    void retryInsideTransactionIsRejected() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                optimisticIssueCouponService.issueWithRetry(1L, 1L, attempt -> {
                })))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------
    // 3. 동시 요청: 시작 래치로 한꺼번에 출발시키고 결과를 기록한다.
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식           | 시나리오           | 재고 | 회원 | 회원당 요청
            PESSIMISTIC      | 재고보다 많은 요청 | 10   | 50   | 1
            OPTIMISTIC       | 재고보다 많은 요청 | 10   | 50   | 1
            OPTIMISTIC_RETRY | 재고보다 많은 요청 | 10   | 50   | 1
            PESSIMISTIC      | 재고 충분          | 50   | 50   | 1
            OPTIMISTIC       | 재고 충분          | 50   | 50   | 1
            OPTIMISTIC_RETRY | 재고 충분          | 50   | 50   | 1
            PESSIMISTIC      | 동일 회원 중복     | 50   | 10   | 5
            OPTIMISTIC       | 동일 회원 중복     | 50   | 10   | 5
            OPTIMISTIC_RETRY | 동일 회원 중복     | 50   | 10   | 5
            """)
    @DisplayName("동시 요청 결과 기록")
    void concurrentRequests(CouponIssueStrategy method, String scenario, int totalCount,
                            int memberCount, int requestsPerMember) throws Exception {
        int requestCount = memberCount * requestsPerMember;
        String deadlock = "";
        for (int round = 1; round <= 3; round++) {
            Burst burst = burst(method, true, totalCount, memberCount, requestsPerMember, round);
            reportBurst(method + ", " + scenario, round, requestCount, burst);

            LabResult result = burst.result();
            if (result.innodbLockMetricsDelta().get("lock_deadlocks") > 0) {
                deadlock = latestDeadlock();
            }
            assertThat(result.requests().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(requestCount);
            assertThat(result.requests()).doesNotContainKey(IssueOutcome.ERROR);
            // 세 방식 모두 정합성 기준은 지켜야 한다.
            assertThat(result.checks().atMostOncePerMember()).isTrue();
            assertThat(result.checks().notOverIssued()).isTrue();
            assertThat(result.checks().issuedCountMatchesRows()).isTrue();
            if (method == CouponIssueStrategy.PESSIMISTIC) {
                // 줄을 서서 차례로 처리되므로 트랜잭션 실패가 없고, 유효 요청이 충분하면 재고만큼 발급된다.
                assertThat(result.requests()).doesNotContainKeys(IssueOutcome.DEADLOCK, IssueOutcome.OPTIMISTIC_CONFLICT);
                if (result.checks().fullyIssued() != null) {
                    assertThat(result.checks().fullyIssued()).isTrue();
                }
            }
            if (method == CouponIssueStrategy.OPTIMISTIC_RETRY) {
                assertThat(result.attempts().histogram().keySet()).allMatch(attempts -> attempts <= maxAttempts);
            }
        }
        append(deadlock.isEmpty() ? "\n" : "\n마지막 데드락:\n\n```text\n" + deadlock + "\n```\n\n");
    }

    private static String histogramText(LabResult result) {
        return result.attempts().histogram().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private static String[] sortedSplit(String plusJoined) {
        String[] parts = plusJoined.split("\\+");
        Arrays.sort(parts);
        return parts;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
