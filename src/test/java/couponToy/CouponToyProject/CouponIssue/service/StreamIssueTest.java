package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService.StreamState;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService.DEAD_LETTER;
import static couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService.GROUP;
import static couponToy.CouponToyProject.CouponIssue.service.StreamIssueCouponService.STREAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 6단계: Redis 예약 뒤 확정 요청을 Redis Stream 에 남기고(XADD), Consumer Group 의 소비자가 DB 확정 뒤 ACK 한다.
 * 소비자는 테스트가 리스너 컨테이너를 직접 만들어 띄운다(앱에서는 stream-consumer 프로필 프로세스).
 * 회수(XAUTOCLAIM)는 앱에서는 1초마다 돌지만, 테스트는 필요한 시점에 직접 부른다(최소 유휴 시간 0).
 * 실행 결과는 build/lab/stage6-stream.md 에 쌓인다.
 */
@SpringBootTest
class StreamIssueTest extends ConcurrencyExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage6-stream.md");

    @Autowired
    private FailurePoints failurePoints;

    @Autowired
    private CouponIssuedRedisRepository redisRepository;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private StreamIssueConsumer consumer;

    @Autowired
    private StreamIssueCouponService streamService;

    private final List<StreamMessageListenerContainer<String, MapRecord<String, String, String>>> containers = new ArrayList<>();

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "6단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    // 스트림은 쿠폰별 키가 아니라 하나라서, 테스트마다 비우고 시작한다
    @BeforeEach
    void emptyStream() {
        redisTemplate.delete(List.of(STREAM, DEAD_LETTER));
    }

    @AfterEach
    void stopConsumers() {
        containers.forEach(StreamMessageListenerContainer::stop);
        containers.clear();
        failurePoints.clear();
    }

    @Test
    void 소비자가_없어도_메시지는_스트림에_남고_나중에_붙은_소비자가_처리한다() throws Exception {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        Map<String, Long> before = consumer.results();

        String first = issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        StreamState queued = streamService.state();
        LabResult beforeConsumer = labService.result(couponId);

        startConsumers("c1");
        awaitResults(before, 1);
        StreamState done = streamService.state();

        assertThat(first).isEqualTo("QUEUED");
        assertThat(queued).isEqualTo(new StreamState(1, 1, 0, 0));
        assertThat(beforeConsumer.issueRows()).isZero();
        assertThat(delta(before)).isEqualTo(Map.of("SUCCESS", 1L));
        assertThat(done).isEqualTo(new StreamState(1, 0, 0, 0)); // ACK 해도 스트림에서 지워지지 않는다
        assertThat(labService.result(couponId).issueRows()).isEqualTo(1);

        append("""
                ## 소비자 없음, 그 뒤 소비자 시작 (재고 1)

                - 소비자 0 에서 A 요청: %s. 스트림 %s, DB 발급 행 %d
                - 소비자를 붙인 뒤: 처리 결과 %s, 스트림 %s, DB 발급 행 1

                """.formatted(first, queued, beforeConsumer.issueRows(), delta(before), done));
    }

    @Test
    void 읽었지만_ACK_전이면_pending_이고_아직_전달되지_않은_메시지는_대기다() throws Exception {
        Long couponId = labCoupon(2);
        long a = labService.createMembers(2).firstMemberId(), b = a + 1;
        Pause pause = new Pause();
        failurePoints.arm(Point.STREAM_BEFORE_CONFIRM, a, pause);
        startConsumers("c1");
        Map<String, Long> before = consumer.results();

        issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        pause.awaitReached();
        issue(couponId, b, CouponIssueStrategy.REDIS_STREAM); // 소비자 하나가 A 에 묶여 있어 B 는 전달되지 않는다
        StreamState held = streamService.state();

        pause.release();
        awaitResults(before, 2);
        StreamState done = streamService.state();

        assertThat(held).isEqualTo(new StreamState(2, 1, 1, 0));
        assertThat(done).isEqualTo(new StreamState(2, 0, 0, 0));
        assertThat(labService.result(couponId).issueRows()).isEqualTo(2);

        append("""
                ## 대기와 pending (재고 2, 소비자 1)

                - 소비자가 A 를 읽고 확정 전에 멈춘 사이 B 요청: 스트림 %s (A 는 pending, B 는 대기)
                - 놓아준 뒤: 스트림 %s, 처리 결과 %s

                """.formatted(held, done, delta(before)));
    }

    @Test
    void DB_커밋_뒤_ACK_전에_죽으면_다시_처리되고_UNIQUE_로_끝난다() throws Exception {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        CountDownLatch crashed = new CountDownLatch(1);
        failurePoints.arm(Point.STREAM_BEFORE_ACK, a, () -> {
            crashed.countDown();
            throw new SimulatedCrash();
        });
        startConsumers("c1");
        Map<String, Long> before = consumer.results();

        issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        assertThat(crashed.await(10, TimeUnit.SECONDS)).isTrue(); // c1 이 커밋 뒤 ACK 전에 멈춘 것을 확인한 뒤 회수한다
        LabResult committed = labService.result(couponId);
        StreamState afterCrash = streamService.state();

        int reclaimed = consumer.reclaim("c2", Duration.ZERO);
        LabResult r = labService.result(couponId);
        StreamState done = streamService.state();

        assertThat(committed.issueRows()).isEqualTo(1);
        assertThat(afterCrash.pending()).isEqualTo(1);
        assertThat(reclaimed).isEqualTo(1);
        assertThat(delta(before)).isEqualTo(Map.of("SUCCESS", 1L, "ALREADY_ISSUED", 1L));
        assertThat(r.issueRows()).isEqualTo(1);
        assertThat(r.issuedCount()).isEqualTo(1); // UNIQUE 위반으로 확정 트랜잭션 전체가 롤백되어 issued_count 는 다시 늘지 않는다
        assertThat(redisRepository.members(couponId)).containsExactly(a);
        assertThat(done.pending()).isZero();

        append("""
                ## DB 커밋 뒤 ACK 전 종료 흉내, 그 뒤 회수 (재고 1)

                - 소비자 c1 이 커밋한 뒤 ACK 전에 Error: DB 발급 행 %d, 스트림 %s
                - c2 가 XAUTOCLAIM 으로 %d건 가져와 다시 처리: 처리 결과 %s, DB 발급 행 %d, issued_count %d, Redis %s, 스트림 %s

                """.formatted(committed.issueRows(), afterCrash, reclaimed, delta(before), r.issueRows(), r.issuedCount(),
                redisRepository.members(couponId), done));
    }

    @Test
    void 일시적인_DB_실패는_ACK_하지_않고_예약도_남겨_다시_처리한다() throws Exception {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        failurePoints.arm(Point.DB_BEFORE_COMMIT, a, () -> {
            throw new DataAccessResourceFailureException("주입한 DB 확정 실패");
        });
        startConsumers("c1");
        Map<String, Long> before = consumer.results();

        issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        awaitResults(before, 1);
        StreamState failed = streamService.state();
        boolean reservedAfterFailure = redisRepository.members(couponId).contains(a);

        consumer.reclaim("c2", Duration.ZERO);
        StreamState done = streamService.state();

        assertThat(failed.pending()).isEqualTo(1);
        assertThat(reservedAfterFailure).isTrue();
        assertThat(delta(before)).isEqualTo(Map.of("RETRY_DataAccessResourceFailureException", 1L, "SUCCESS", 1L));
        assertThat(done.pending()).isZero();
        assertThat(labService.result(couponId).issueRows()).isEqualTo(1);

        append("""
                ## 일시적 DB 실패 뒤 재시도 (재고 1)

                - 첫 처리 실패: 스트림 %s, Redis 예약 남음 %s (ACK 도 보상도 하지 않음)
                - 회수 뒤 다시 처리: 처리 결과 %s, 스트림 %s, DB 발급 행 1

                """.formatted(failed, reservedAfterFailure, delta(before), done));
    }

    @Test
    void 계속_실패하면_전달_횟수_상한을_넘긴_뒤_따로_보관하고_예약을_되돌린다() throws Exception {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        failurePoints.arm(Point.DB_BEFORE_COMMIT, a, new FailurePoints.Action() {
            @Override
            public void run() {
                failurePoints.arm(Point.DB_BEFORE_COMMIT, a, this); // 다음 처리에서도 실패하도록 다시 건다
                throw new DataAccessResourceFailureException("주입한 DB 확정 실패(계속)");
            }
        });
        startConsumers("c1");
        Map<String, Long> before = consumer.results();

        issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        awaitResults(before, 1);
        List<StreamState> afterEachReclaim = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            consumer.reclaim("c2", Duration.ZERO);
            afterEachReclaim.add(streamService.state());
        }
        boolean reserved = redisRepository.members(couponId).contains(a);
        failurePoints.clear();
        String retry = issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        awaitResults(before, 5);

        assertThat(delta(before)).isEqualTo(Map.of("RETRY_DataAccessResourceFailureException", 3L, "DEAD_LETTER", 1L, "SUCCESS", 1L));
        assertThat(afterEachReclaim.get(2)).isEqualTo(new StreamState(1, 0, 0, 1));
        assertThat(reserved).isFalse();
        assertThat(retry).isEqualTo("QUEUED");
        assertThat(labService.result(couponId).issueRows()).isEqualTo(1);

        append("""
                ## 계속 실패: 전달 횟수 상한(3) 뒤 따로 보관 (재고 1)

                - 첫 처리와 회수 3번 뒤 스트림 상태: %s
                - 따로 보관한 뒤 Redis 예약 남음 %s. A 재요청 %s → 처리 결과 누적 %s

                """.formatted(afterEachReclaim, reserved, retry, delta(before)));
    }

    @Test
    void 같은_그룹의_소비자_2개는_메시지를_나눠_처리한다() throws Exception {
        Long couponId = labCoupon(20);
        long first = labService.createMembers(20).firstMemberId();
        startConsumers("c1", "c2");
        Map<String, Long> before = consumer.results();

        for (long id = first; id < first + 20; id++) {
            issue(couponId, id, CouponIssueStrategy.REDIS_STREAM);
        }
        awaitResults(before, 20);
        LabResult r = labService.result(couponId);

        assertThat(delta(before)).isEqualTo(Map.of("SUCCESS", 20L)); // 5단계 구독자 2개와 달리 두 번 처리하지 않는다
        assertThat(r.issueRows()).isEqualTo(20);
        assertThat(streamService.state().pending()).isZero();

        append("""
                ## 같은 그룹의 소비자 2개 (재고 20, 요청 20)

                - 처리 결과 %s, DB 발급 행 %d, 스트림 %s

                """.formatted(delta(before), r.issueRows(), streamService.state()));
    }

    @Test
    void 발행자가_예약_뒤_XADD_전에_죽으면_예약만_남고_예약과_XADD_를_한_스크립트로_하면_그_틈이_없다() throws Exception {
        Long couponId = labCoupon(2);
        long a = labService.createMembers(2).firstMemberId(), b = a + 1;
        failurePoints.arm(Point.AFTER_RESERVE, a, () -> {
            throw new SimulatedCrash();
        });
        startConsumers("c1");
        Map<String, Long> before = consumer.results();

        String aFirst = issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        StreamState afterCrash = streamService.state();
        boolean aReserved = redisRepository.members(couponId).contains(a);
        String aAgain = issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);

        String bFirst = issue(couponId, b, CouponIssueStrategy.REDIS_STREAM_ATOMIC);
        awaitResults(before, 1);
        LabResult r = labService.result(couponId);

        assertThat(aFirst).isEqualTo("CRASHED");
        assertThat(afterCrash.length()).isZero();
        assertThat(aReserved).isTrue();
        assertThat(aAgain).isEqualTo("DUPLICATE");
        assertThat(bFirst).isEqualTo("QUEUED");
        assertThat(r.issueRows()).isEqualTo(1);
        assertThat(r.redisOnlyMembers()).isEqualTo(1); // A

        append("""
                ## 발행자가 예약 뒤 XADD 전에 종료 흉내 / 예약과 XADD 를 한 스크립트로 (재고 2)

                - REDIS_STREAM, A: %s. 스트림 길이 %d, Redis 에 A 예약 %s → A 재요청 %s (쿠폰 없음)
                - REDIS_STREAM_ATOMIC, B: %s → 처리 결과 %s. DB 발급 행 %d, Redis 에만 있는 회원 %d (A)

                """.formatted(aFirst, afterCrash.length(), aReserved, aAgain, bFirst, delta(before), r.issueRows(), r.redisOnlyMembers()));
    }

    @Test
    void 한_스크립트_예약은_XADD_가_실패하면_예약도_하지_않는다() {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        redisTemplate.opsForValue().set(STREAM, "not-a-stream"); // 스트림 키를 다른 자료형으로 막아 XADD 를 실패시킨다

        String atomic = issue(couponId, a, CouponIssueStrategy.REDIS_STREAM_ATOMIC);
        boolean reservedAfterAtomic = redisRepository.members(couponId).contains(a);
        String twoStep = issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        boolean reservedAfterTwoStep = redisRepository.members(couponId).contains(a);

        assertThat(atomic).isEqualTo("ERROR");
        assertThat(reservedAfterAtomic).isFalse();
        assertThat(twoStep).isEqualTo("ERROR");
        assertThat(reservedAfterTwoStep).isFalse(); // XADD 실패를 잡아 예약을 되돌렸다

        append("""
                ## XADD 실패 (스트림 키를 문자열로 막음, 재고 1)

                - REDIS_STREAM_ATOMIC: %s, 예약 남음 %s (스크립트에서 XADD 가 SADD 보다 먼저라 SADD 까지 가지 않음)
                - REDIS_STREAM: %s, 예약 남음 %s (예약 뒤 XADD 예외를 잡아 SREM)

                """.formatted(atomic, reservedAfterAtomic, twoStep, reservedAfterTwoStep));
    }

    @Test
    void 처리_전에_스트림이_잘리면_그_메시지는_사라진다() throws Exception {
        Long couponId = labCoupon(2);
        long a = labService.createMembers(2).firstMemberId(), b = a + 1;
        Map<String, Long> before = consumer.results();

        issue(couponId, a, CouponIssueStrategy.REDIS_STREAM);
        issue(couponId, b, CouponIssueStrategy.REDIS_STREAM);
        long trimmed = redisTemplate.opsForStream().trim(STREAM, 1); // XTRIM MAXLEN 1: 오래된 A 를 지운다
        startConsumers("c1");
        awaitResults(before, 1);
        LabResult r = labService.result(couponId);

        assertThat(trimmed).isEqualTo(1);
        assertThat(delta(before)).isEqualTo(Map.of("SUCCESS", 1L));
        assertThat(r.issueRows()).isEqualTo(1);
        assertThat(r.redisOnlyMembers()).isEqualTo(1);
        assertThat(redisRepository.members(couponId)).contains(a);

        append("""
                ## 처리 전에 스트림을 자름 (XTRIM MAXLEN 1, 재고 2)

                - A, B 를 넣고 소비자 없이 자름: 지운 메시지 %d건
                - 소비자를 붙인 뒤: 처리 결과 %s, DB 발급 행 %d, Redis 에만 있는 회원 %d (A)

                """.formatted(trimmed, delta(before), r.issueRows(), r.redisOnlyMembers()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            재고보다 많은 요청 | 10 | 50 | 1
            재고 충분           | 50 | 50 | 1
            동일 회원 중복      | 50 | 10 | 5
            """)
    void concurrentRequests(String scenario, int totalCount, int memberCount, int requestsPerMember) throws Exception {
        startConsumers("c1", "c2", "c3", "c4");
        for (int round = 1; round <= 3; round++) {
            Map<String, Long> before = consumer.results();
            Burst accepted = burst(CouponIssueStrategy.REDIS_STREAM, true, totalCount, memberCount, requestsPerMember, round);
            long settleStart = System.nanoTime();
            awaitResults(before, accepted.result().requests().get(IssueOutcome.QUEUED));
            long settleMs = (System.nanoTime() - settleStart) / 1_000_000;
            LabResult r = labService.result(accepted.result().couponId());

            assertThat(r.checks().atMostOncePerMember()).isTrue();
            assertThat(r.checks().notOverIssued()).isTrue();
            assertThat(r.checks().issuedCountMatchesRows()).isTrue();
            assertThat(r.issueRows()).isEqualTo(Math.min(totalCount, memberCount));
            assertThat(delta(before)).isEqualTo(Map.of("SUCCESS", r.issueRows()));
            assertThat(r.redisOnlyMembers()).isZero();
            assertThat(r.dbOnlyMembers()).isZero();
            assertThat(r.stream().pending()).isZero();

            if (round == 1) {
                append("\n## 동시 요청: REDIS_STREAM, " + scenario + " (재고 " + r.totalCount() + ", 요청 " + memberCount * requestsPerMember
                        + ", 요청 스레드 " + memberCount * requestsPerMember + ", 같은 그룹 소비자 4)\n\n"
                        + "| 회차 | 접수 결과 | 소비자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms | 스트림 |\n"
                        + "|---:|---|---|---:|---:|---|---:|---|---:|---:|---|\n");
            }
            long n = Math.min(r.totalCount(), r.ordering().validRequests());
            append("| %d | %s | %s | %d | %d | %d / %d | %d/%d | %.0f/%.0f/%.0f | %d | %d | %s |%n".formatted(
                    round, r.requests(), delta(before), r.issueRows(), r.issuedCount(), r.redisOnlyMembers(), r.dbOnlyMembers(),
                    r.ordering().firstNValidWon(), n, r.latencyMs().p50(), r.latencyMs().p95(), r.latencyMs().p99(),
                    accepted.wallMs(), settleMs, r.stream()));
        }
    }

    private void startConsumers(String... names) {
        consumer.ensureGroup();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options = StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(100))
                .batchSize(10)
                .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container = StreamMessageListenerContainer.create(connectionFactory, options);
        for (String name : names) {
            container.receive(Consumer.from(GROUP, name), StreamOffset.create(STREAM, ReadOffset.lastConsumed()), consumer);
        }
        container.start();
        containers.add(container);
    }

    private String issue(Long couponId, long memberId, CouponIssueStrategy method) {
        try {
            labService.issue(couponId, memberId, method);
            return "QUEUED";
        } catch (SimulatedCrash crash) {
            return "CRASHED";
        } catch (RuntimeException e) {
            return IssueOutcome.of(e).name();
        }
    }

    // 소비자 처리 결과가 before 이후 expected 건 쌓일 때까지 기다린다
    private void awaitResults(Map<String, Long> before, long expected) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(10))
                .until(() -> delta(before).values().stream().mapToLong(Long::longValue).sum() >= expected);
    }

    private Map<String, Long> delta(Map<String, Long> before) {
        Map<String, Long> delta = new TreeMap<>();
        consumer.results().forEach((k, v) -> {
            long d = v - before.getOrDefault(k, 0L);
            if (d > 0) delta.put(k, d);
        });
        return delta;
    }
}
