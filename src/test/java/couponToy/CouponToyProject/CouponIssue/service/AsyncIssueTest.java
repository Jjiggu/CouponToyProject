package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import couponToy.CouponToyProject.Lab.service.LabService.MemberRange;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static couponToy.CouponToyProject.Lab.service.IssueOutcome.ERROR;
import static couponToy.CouponToyProject.Lab.service.IssueOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 4단계: Redis 예약(3단계 Lua)은 요청 스레드에서, DB 확정(같은 IssueConfirmService)은 @Async 스레드 풀에서 한다.
 * 요청 스레드는 스레드 풀 큐에 작업을 넣으면 바로 돌아온다(접수, HTTP 202). 실행 결과는 build/lab/stage4-async.md 에 쌓인다.
 * 큐 포화를 재현하려고 큐 용량을 50 으로 줄였다(앱 기본 50000). 작업 스레드(coupon-issue-async-N)는 기본 4개.
 */
@SpringBootTest(properties = "coupon.issue.async.queue-capacity=" + AsyncIssueTest.QUEUE_CAPACITY)
class AsyncIssueTest extends ConcurrencyExperimentSupport {

    static final int QUEUE_CAPACITY = 50;
    private static final Path REPORT = Path.of("build/lab/stage4-async.md");

    @Autowired
    private FailurePoints failurePoints;

    @Autowired
    private CouponIssuedRedisRepository redisRepository;

    @Autowired
    private ThreadPoolTaskExecutor executor;

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "4단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    // 데이터 정리(상위 클래스) 전에 풀이 비어야 다음 테스트에 확정이 섞이지 않는다
    @AfterEach
    void drain() {
        failurePoints.clear();
        await().atMost(Duration.ofSeconds(30)).until(() -> executor.getActiveCount() == 0 && executor.getQueueSize() == 0);
    }

    @Test
    void 접수는_발급이_아니다() throws Exception {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(2).firstMemberId(), b = a + 1;
        String[] worker = new String[1];
        Pause pause = new Pause();
        failurePoints.arm(Point.ASYNC_BEFORE_CONFIRM, a, () -> {
            worker[0] = Thread.currentThread().getName() + ", 트랜잭션 " + TransactionSynchronizationManager.isActualTransactionActive();
            pause.run();
        });

        String request = Thread.currentThread().getName();
        assertThat(issue(couponId, a)).isEqualTo("ACCEPTED");
        pause.awaitReached();
        LabResult accepted = labService.result(couponId);
        // 접수됐지만 미완료: Redis 에는 예약, DB 에는 아무것도 없다
        assertThat(accepted.pending()).isEqualTo(1);
        assertThat(redisRepository.members(couponId)).containsExactly(a);
        assertThat(accepted.issueRows()).isZero();
        // 이 사이 A 는 쿠폰이 없는데 "이미 발급", B 는 "품절"
        String aAgain = issue(couponId, a);
        String bMeanwhile = issue(couponId, b);
        assertThat(aAgain).isEqualTo("DUPLICATE");
        assertThat(bMeanwhile).isEqualTo("SOLD_OUT");
        assertThat(worker[0]).startsWith("coupon-issue-async-").endsWith("트랜잭션 false");

        pause.release();
        LabResult done = awaitConfirmed(couponId);
        assertThat(done.asyncResults()).isEqualTo(Map.of(SUCCESS, 1L));
        assertThat(done.issueRows()).isEqualTo(1);

        append("""
                ## 접수 직후 DB 확정 전 (재고 1)

                - 요청 스레드 `%s` 에서 접수(ACCEPTED). DB 확정은 `%s` 에서 실행
                - 확정 전: 미완료 %d, Redis %s, DB 발급 행 %d → 이때 A 재요청 %s, B 요청 %s
                - 확정 후: 확정 결과 %s, DB 발급 행 %d

                """.formatted(request, worker[0], accepted.pending(), redisRepository.members(couponId), accepted.issueRows(),
                aAgain, bMeanwhile, done.asyncResults(), done.issueRows()));
    }

    @Test
    void 접수_뒤_DB_확정이_실패해도_응답은_이미_접수다() throws Exception {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        failurePoints.arm(Point.DB_BEFORE_COMMIT, a, () -> {
            throw new DataAccessResourceFailureException("주입한 DB 확정 실패");
        });

        String first = issue(couponId, a);
        LabResult failed = awaitConfirmed(couponId);
        assertThat(first).isEqualTo("ACCEPTED");
        assertThat(failed.asyncResults()).isEqualTo(Map.of(ERROR, 1L));
        assertThat(failed.issueRows()).isZero();
        assertThat(redisRepository.members(couponId)).isEmpty(); // 작업 스레드에서 보상 SREM

        String retry = issue(couponId, a);
        LabResult done = awaitConfirmed(couponId);
        assertThat(retry).isEqualTo("ACCEPTED");
        assertThat(done.asyncResults()).isEqualTo(Map.of(SUCCESS, 1L, ERROR, 1L));
        assertThat(done.issueRows()).isEqualTo(1);

        append("""
                ## 접수 뒤 DB 확정 실패 (재고 1)

                - A 요청: %s → 확정 결과 %s, DB 발급 행 %d, Redis 예약 %s
                - A 재요청: %s → 확정 결과 누적 %s, DB 발급 행 %d

                """.formatted(first, failed.asyncResults(), failed.issueRows(), "없음(보상)", retry, done.asyncResults(), done.issueRows()));
    }

    @Test
    void 큐가_가득_차면_접수를_거부하고_예약을_되돌린다() throws Exception {
        Saturated full = saturate();
        String overflow = issue(full.couponId(), full.overflow());
        long reservedAfterReject = redisRepository.issuedCount(full.couponId());

        assertThat(overflow).isEqualTo("REJECTED");
        assertThat(redisRepository.members(full.couponId())).doesNotContain(full.overflow());
        assertThat(reservedAfterReject).isEqualTo(full.accepted());

        full.busy().forEach(Pause::release);
        LabResult drained = awaitConfirmed(full.couponId());
        assertThat(drained.asyncResults()).isEqualTo(Map.of(SUCCESS, (long) full.accepted()));

        String retry = issue(full.couponId(), full.overflow());
        LabResult done = awaitConfirmed(full.couponId());
        assertThat(retry).isEqualTo("ACCEPTED");
        assertThat(done.issueRows()).isEqualTo(full.accepted() + 1);

        append("""
                ## 큐 포화: AbortPolicy (기본값, 작업 스레드 %d, 큐 %d, 재고 1000)

                - 작업 스레드 %d개를 DB 확정 직전에 멈추고 %d건을 더 보냄 → 큐 %d건
                - 다음 요청: %s (HTTP 503), 직후 Redis 예약 %d건 (거부된 회원 없음)
                - 멈춘 작업 스레드를 놓아준 뒤: 요청 결과 %s, 확정 결과 %s
                - 거부된 회원 재요청: %s → DB 발급 행 %d

                """.formatted(full.threads(), full.capacity(), full.threads(), full.capacity(), full.queued(), overflow, reservedAfterReject,
                drained.requests(), drained.asyncResults(), retry, done.issueRows()));
    }

    // 거부 정책은 실행 중인 풀에서 잠시 바꿔 본다. 끝나면 AbortPolicy 로 되돌린다.
    @Test
    void 큐가_가득_찼을_때_CallerRunsPolicy_면_요청_스레드가_직접_확정한다() throws Exception {
        executor.getThreadPoolExecutor().setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            Saturated full = saturate();
            String[] ranOn = new String[1];
            failurePoints.arm(Point.ASYNC_BEFORE_CONFIRM, full.overflow(), () -> ranOn[0] = Thread.currentThread().getName());

            long startedAt = System.nanoTime();
            String overflow = issue(full.couponId(), full.overflow());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            boolean rowRightAfter = issueCouponRepository.existsByMemberIdAndCouponId(full.overflow(), full.couponId());

            assertThat(overflow).isEqualTo("ACCEPTED");
            assertThat(ranOn[0]).isEqualTo(Thread.currentThread().getName());
            assertThat(rowRightAfter).isTrue(); // 응답 전에 이미 커밋됐다. 다른 작업은 아직 멈춰 있다

            full.busy().forEach(Pause::release);
            LabResult done = awaitConfirmed(full.couponId());
            assertThat(done.issueRows()).isEqualTo(full.accepted() + 1);

            append("""
                    ## 큐 포화: CallerRunsPolicy (작업 스레드 %d, 큐 %d)

                    - 넘친 요청: %s, DB 확정을 `%s` 스레드(요청 스레드)가 직접 실행, 응답까지 %d ms, 응답 직후 DB 발급 행 있음 %s
                    - 앞서 큐에 있던 작업은 아직 멈춰 있는데 넘친 요청이 먼저 커밋됐다. 멈춘 작업 스레드를 놓아준 뒤 DB 발급 행 %d

                    """.formatted(full.threads(), full.capacity(), overflow, ranOn[0], elapsedMs, rowRightAfter, done.issueRows()));
        } finally {
            executor.getThreadPoolExecutor().setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        }
    }

    @Test
    void 큐가_가득_찼을_때_DiscardPolicy_면_접수된_작업이_조용히_사라진다() throws Exception {
        executor.getThreadPoolExecutor().setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        try {
            Saturated full = saturate();
            String overflow = issue(full.couponId(), full.overflow());

            full.busy().forEach(Pause::release);
            await().atMost(Duration.ofSeconds(30)).until(() -> executor.getActiveCount() == 0 && executor.getQueueSize() == 0);
            LabResult r = labService.result(full.couponId());
            String retry = issue(full.couponId(), full.overflow());

            assertThat(overflow).isEqualTo("ACCEPTED"); // 예외 없이 202
            assertThat(r.pending()).isEqualTo(1);      // 확정 결과가 영영 오지 않는다
            assertThat(r.redisOnlyMembers()).isEqualTo(1);
            assertThat(issueCouponRepository.existsByMemberIdAndCouponId(full.overflow(), full.couponId())).isFalse();
            assertThat(retry).isEqualTo("DUPLICATE");

            append("""
                    ## 큐 포화: DiscardPolicy (작업 스레드 %d, 큐 %d)

                    - 넘친 요청: %s (예외 없음). 작업 스레드와 큐가 모두 빈 뒤: 확정 결과 %s, 미완료 %d, Redis 에만 있는 회원 %d, 그 회원의 DB 발급 행 없음
                    - 그 회원 재요청: %s

                    """.formatted(full.threads(), full.capacity(), overflow, r.asyncResults(), r.pending(), r.redisOnlyMembers(), retry));
        } finally {
            executor.getThreadPoolExecutor().setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            재고보다 많은 요청 | 10 | 50 | 1
            재고 충분           | 50 | 50 | 1
            동일 회원 중복      | 50 | 10 | 5
            """)
    void concurrentRequests(String scenario, int totalCount, int memberCount, int requestsPerMember) throws Exception {
        for (int round = 1; round <= 3; round++) {
            Burst accepted = burst(CouponIssueStrategy.REDIS_ASYNC, true, totalCount, memberCount, requestsPerMember, round);
            LabResult r = awaitConfirmed(accepted.result().couponId());

            assertThat(r.checks().atMostOncePerMember()).isTrue();
            assertThat(r.checks().notOverIssued()).isTrue();
            assertThat(r.checks().issuedCountMatchesRows()).isTrue();
            assertThat(r.issueRows()).isEqualTo(Math.min(totalCount, memberCount));
            assertThat(r.requests().get(IssueOutcome.ACCEPTED)).isEqualTo(r.issueRows());
            assertThat(r.asyncResults()).isEqualTo(Map.of(SUCCESS, r.issueRows()));
            assertThat(r.redisOnlyMembers()).isZero();
            assertThat(r.dbOnlyMembers()).isZero();

            reportAsyncBurst(scenario, round, memberCount * requestsPerMember, accepted, r);
        }
    }

    private record Saturated(Long couponId, int threads, int capacity, int queued, List<Pause> busy, long overflow) {
        int accepted() {
            return threads + capacity;
        }
    }

    // 작업 스레드를 모두 DB 확정 직전에 멈추고 큐를 가득 채운다. overflow 는 아직 보내지 않은 다음 회원
    private Saturated saturate() throws Exception {
        int threads = executor.getCorePoolSize();
        int capacity = executor.getQueueCapacity();
        Long couponId = labCoupon(1000);
        MemberRange members = labService.createMembers(threads + capacity + 1);
        long first = members.firstMemberId();

        List<Pause> busy = new ArrayList<>();
        for (long id = first; id < first + threads; id++) {
            Pause pause = new Pause();
            failurePoints.arm(Point.ASYNC_BEFORE_CONFIRM, id, pause);
            assertThat(issue(couponId, id)).isEqualTo("ACCEPTED");
            pause.awaitReached();
            busy.add(pause);
        }
        for (long id = first + threads; id < members.lastMemberId(); id++) {
            assertThat(issue(couponId, id)).isEqualTo("ACCEPTED");
        }
        assertThat(executor.getQueueSize()).isEqualTo(capacity);
        return new Saturated(couponId, threads, capacity, executor.getQueueSize(), busy, members.lastMemberId());
    }

    private String issue(Long couponId, long memberId) {
        try {
            labService.issue(couponId, memberId, CouponIssueStrategy.REDIS_ASYNC);
            return "ACCEPTED";
        } catch (RuntimeException e) {
            return IssueOutcome.of(e).name();
        }
    }

    // 접수된 요청의 확정 결과가 모두 나올 때까지 기다린다
    private LabResult awaitConfirmed(Long couponId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> labService.result(couponId).pending() == 0);
        return labService.result(couponId);
    }

    private void reportAsyncBurst(String scenario, int round, int requestCount, Burst accepted, LabResult r) {
        if (round == 1) {
            append("\n## 동시 요청: REDIS_ASYNC, " + scenario + " (재고 " + r.totalCount() + ", 요청 " + requestCount
                    + ", 요청 스레드 " + requestCount + ", 작업 스레드 " + executor.getCorePoolSize() + ", 큐 " + executor.getQueueCapacity() + ")\n\n"
                    + "| 회차 | 접수 결과 | 확정 결과 | 마지막 접수 반환 시점 미완료 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 완료 순서 역전 | 접수 p50/p95/p99 ms | 진입→커밋 p50/p95/p99 ms | 접수 총 소요 ms |\n"
                    + "|---:|---|---|---:|---:|---:|---|---:|---:|---|---|---:|\n");
        }
        long n = Math.min(r.totalCount(), r.ordering().validRequests());
        append("| %d | %s | %s | %d | %d | %d | %d / %d | %d/%d | %d | %.0f/%.0f/%.0f | %.0f/%.0f/%.0f | %d |%n".formatted(
                round, r.requests(), r.asyncResults(), accepted.result().pending(), r.issueRows(), r.issuedCount(),
                r.redisOnlyMembers(), r.dbOnlyMembers(), r.ordering().firstNValidWon(), n, r.ordering().completedOutOfArrivalOrder(),
                r.latencyMs().p50(), r.latencyMs().p95(), r.latencyMs().p99(),
                r.confirmLatencyMs().p50(), r.confirmLatencyMs().p95(), r.confirmLatencyMs().p99(), accepted.wallMs()));
    }
}
