package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 5단계: Redis 예약(3단계 Lua) 뒤 Redis Pub/Sub 으로 DB 확정 요청을 보내고, 구독자가 3단계와 같은 확정·보상을 한다.
 * 구독자는 테스트가 리스너 컨테이너를 직접 띄워 만든다(앱에서는 pubsub-consumer 프로필 프로세스가 구독).
 * 발행자는 확정 결과를 받지 못하므로, 구독자 쪽 처리 결과(PubSubIssueConsumer.results)와 DB·Redis 상태를 본다.
 * 실행 결과는 build/lab/stage5-pubsub.md 에 쌓인다.
 */
@SpringBootTest
class PubSubIssueTest extends ConcurrencyExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage5-pubsub.md");

    @Autowired
    private FailurePoints failurePoints;

    @Autowired
    private CouponIssuedRedisRepository redisRepository;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private PubSubIssueConsumer consumer;

    @Autowired
    private ThreadPoolTaskExecutor executor;

    private final List<RedisMessageListenerContainer> subscribers = new ArrayList<>();

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "5단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    @AfterEach
    void unsubscribe() throws Exception {
        for (RedisMessageListenerContainer container : subscribers) {
            container.destroy();
        }
        subscribers.clear();
        failurePoints.clear();
        await().atMost(Duration.ofSeconds(30)).until(() -> executor.getActiveCount() == 0 && executor.getQueueSize() == 0);
    }

    @Test
    void 구독자가_없는_동안_발행한_메시지는_구독자가_다시_붙어도_오지_않는다() throws Exception {
        Long couponId = labCoupon(1);
        long a = labService.createMembers(2).firstMemberId(), b = a + 1;
        assertThat(subscriberCount()).isZero();

        String first = issue(couponId, a);
        LabResult published = labService.result(couponId);
        assertThat(first).isEqualTo("PUBLISHED");
        assertThat(published.publishReceivers()).isEqualTo(Map.of(0L, 1L));

        // 구독자가 붙은 뒤 다른 쿠폰으로 메시지를 하나 보내 처리를 확인한다. 같은 채널은 보낸 순서대로 전달되므로,
        // 이 메시지가 처리됐다면 그 전에 보낸 A 의 메시지가 아직 전달 중일 수는 없다.
        subscribe();
        Long markerCoupon = labCoupon(1);
        long marker = labService.createMembers(1).firstMemberId();
        assertThat(issue(markerCoupon, marker)).isEqualTo("PUBLISHED");
        await().atMost(Duration.ofSeconds(10)).until(() -> issueCouponRepository.existsByMemberIdAndCouponId(marker, markerCoupon));
        awaitIdle();

        LabResult after = labService.result(couponId);
        String aAgain = issue(couponId, a);
        String bRequest = issue(couponId, b);
        assertThat(after.issueRows()).isZero();
        assertThat(after.redisOnlyMembers()).isEqualTo(1);
        assertThat(aAgain).isEqualTo("DUPLICATE");
        assertThat(bRequest).isEqualTo("SOLD_OUT");

        append("""
                ## 구독자 없음, 그 뒤 구독 (재고 1)

                - 구독자 0 에서 A 요청: %s, PUBLISH 가 돌려준 구독자 수 %s
                - 구독자를 붙이고 다른 쿠폰 메시지가 처리된 뒤: DB 발급 행 %d, Redis 에만 있는 회원 %d (A 의 메시지는 오지 않음)
                - A 재요청 %s, B 요청 %s

                """.formatted(first, published.publishReceivers(), after.issueRows(), after.redisOnlyMembers(), aAgain, bRequest));
    }

    @Test
    void 구독자가_받으면_작업_스레드가_확정한다() throws Exception {
        subscribe();
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        String[] worker = new String[1];
        failurePoints.arm(Point.PUBSUB_BEFORE_CONFIRM, a, () -> worker[0] = Thread.currentThread().getName());
        Map<String, Long> before = consumer.results();

        String first = issue(couponId, a);
        await().atMost(Duration.ofSeconds(10)).until(() -> issueCouponRepository.existsByMemberIdAndCouponId(a, couponId));
        awaitIdle();
        LabResult r = labService.result(couponId);

        assertThat(first).isEqualTo("PUBLISHED");
        assertThat(r.publishReceivers()).isEqualTo(Map.of(1L, 1L));
        assertThat(worker[0]).startsWith("coupon-issue-async-");
        assertThat(delta(before)).isEqualTo(Map.of("SUCCESS", 1L));
        assertThat(r.issueRows()).isEqualTo(1);
        assertThat(r.redisOnlyMembers()).isZero();

        append("""
                ## 구독자 1개, 정상 전달 (재고 1)

                - A 요청: %s, PUBLISH 가 돌려준 구독자 수 %s. 구독자는 `%s` 에서 확정
                - 구독자 처리 결과 %s, DB 발급 행 %d, Redis 에만 있는 회원 %d

                """.formatted(first, r.publishReceivers(), worker[0], delta(before), r.issueRows(), r.redisOnlyMembers()));
    }

    @Test
    void 구독자가_받은_뒤_DB_확정이_실패하면_구독자가_예약을_되돌린다() throws Exception {
        subscribe();
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        failurePoints.arm(Point.DB_BEFORE_COMMIT, a, () -> {
            throw new DataAccessResourceFailureException("주입한 DB 확정 실패");
        });
        Map<String, Long> before = consumer.results();

        String first = issue(couponId, a);
        awaitProcessed(before, 1);
        LabResult failed = labService.result(couponId);
        Map<String, Long> failedResults = delta(before);

        String retry = issue(couponId, a);
        awaitProcessed(before, 2);
        LabResult done = labService.result(couponId);

        assertThat(first).isEqualTo("PUBLISHED");
        assertThat(failedResults).isEqualTo(Map.of("DataAccessResourceFailureException", 1L));
        assertThat(failed.issueRows()).isZero();
        assertThat(redisRepository.members(couponId)).contains(a); // 재요청이 다시 예약했다
        assertThat(failed.redisOnlyMembers()).isZero();            // 실패 직후에는 보상으로 예약이 없었다
        assertThat(retry).isEqualTo("PUBLISHED");
        assertThat(done.issueRows()).isEqualTo(1);

        append("""
                ## 구독자가 받은 뒤 DB 확정 실패 (재고 1)

                - A 요청: %s → 구독자 처리 결과 %s, DB 발급 행 %d, Redis 에만 있는 회원 %d (구독자가 보상 SREM)
                - A 재요청: %s → 구독자 처리 결과 누적 %s, DB 발급 행 %d

                """.formatted(first, failedResults, failed.issueRows(), failed.redisOnlyMembers(), retry, delta(before), done.issueRows()));
    }

    @Test
    void 구독자가_받은_뒤_처리하기_전에_죽으면_메시지가_사라진다() throws Exception {
        subscribe();
        Long couponId = labCoupon(1);
        long a = labService.createMembers(2).firstMemberId(), b = a + 1;
        CountDownLatch crashed = new CountDownLatch(1);
        failurePoints.arm(Point.PUBSUB_BEFORE_CONFIRM, a, () -> {
            crashed.countDown();
            throw new SimulatedCrash();
        });
        Map<String, Long> before = consumer.results();

        String first = issue(couponId, a);
        assertThat(crashed.await(10, TimeUnit.SECONDS)).isTrue();
        awaitIdle();
        LabResult r = labService.result(couponId);
        String aAgain = issue(couponId, a);
        String bRequest = issue(couponId, b);

        assertThat(first).isEqualTo("PUBLISHED");
        assertThat(r.publishReceivers()).isEqualTo(Map.of(1L, 1L)); // 구독자는 받았다
        assertThat(delta(before)).isEmpty();                         // 처리 결과는 없다
        assertThat(r.issueRows()).isZero();
        assertThat(r.redisOnlyMembers()).isEqualTo(1);
        assertThat(aAgain).isEqualTo("DUPLICATE");
        assertThat(bRequest).isEqualTo("SOLD_OUT");

        append("""
                ## 구독자가 받은 뒤 처리 전에 종료 흉내 (재고 1)

                - A 요청: %s, PUBLISH 가 돌려준 구독자 수 %s. 구독자 작업 스레드에서 확정 전에 Error 를 던짐(보상 실행 안 됨)
                - 구독자 처리 결과 %s, DB 발급 행 %d, Redis 에만 있는 회원 %d
                - A 재요청 %s, B 요청 %s

                """.formatted(first, r.publishReceivers(), delta(before), r.issueRows(), r.redisOnlyMembers(), aAgain, bRequest));
    }

    @Test
    void 구독자가_2개면_같은_메시지를_둘_다_처리한다() throws Exception {
        subscribe();
        subscribe();
        Long couponId = labCoupon(1);
        long a = labService.createMembers(1).firstMemberId();
        Map<String, Long> before = consumer.results();

        String first = issue(couponId, a);
        awaitProcessed(before, 2);
        LabResult r = labService.result(couponId);

        assertThat(r.publishReceivers()).isEqualTo(Map.of(2L, 1L));
        assertThat(delta(before)).isEqualTo(Map.of("SUCCESS", 1L, "DataIntegrityViolationException", 1L));
        assertThat(r.issueRows()).isEqualTo(1);
        assertThat(r.issuedCount()).isEqualTo(1);
        assertThat(redisRepository.members(couponId)).containsExactly(a);

        append("""
                ## 구독자 2개 (재고 1)

                - A 요청: %s, PUBLISH 가 돌려준 구독자 수 %s
                - 구독자 처리 결과 %s, DB 발급 행 %d, issued_count %d, Redis %s

                """.formatted(first, r.publishReceivers(), delta(before), r.issueRows(), r.issuedCount(), redisRepository.members(couponId)));
    }

    @Test
    void 구독자_2개_중_하나가_실패하면_그_보상이_다른_구독자의_발급을_Redis_에서_지운다() throws Exception {
        subscribe();
        subscribe();
        Long couponId = labCoupon(1);
        long a = labService.createMembers(2).firstMemberId(), b = a + 1;
        // 먼저 커밋 직전에 닿은 구독자 하나만 실패한다(실패 지점은 한 번만 동작). 다른 구독자는 그 롤백을 기다렸다가 성공한다.
        failurePoints.arm(Point.DB_BEFORE_COMMIT, a, () -> {
            throw new DataAccessResourceFailureException("주입한 DB 확정 실패");
        });
        Map<String, Long> before = consumer.results();

        String aFirst = issue(couponId, a);
        awaitProcessed(before, 2);
        LabResult split = labService.result(couponId);
        Map<String, Long> splitResults = delta(before);
        Set<Long> redisAfterSplit = redisRepository.members(couponId);

        // Redis 는 A 를 잊어 재고가 남은 것으로 보고 B 를 받는다. DB 조건부 UPDATE 가 막는다.
        String bRequest = issue(couponId, b);
        awaitProcessed(before, 4);
        LabResult afterB = labService.result(couponId);
        Map<String, Long> resultsAfterB = delta(before);
        Set<Long> redisAfterB = redisRepository.members(couponId);

        // A 가 다시 요청하면 Redis 는 통과시키고 DB UNIQUE 가 막는다. UNIQUE 위반은 보상하지 않으므로 Redis 가 A 를 다시 기록한다.
        String aAgain = issue(couponId, a);
        awaitProcessed(before, 6);
        LabResult afterA = labService.result(couponId);

        assertThat(splitResults).isEqualTo(Map.of("SUCCESS", 1L, "DataAccessResourceFailureException", 1L));
        assertThat(split.issueRows()).isEqualTo(1);
        assertThat(split.dbOnlyMembers()).isEqualTo(1); // DB 에는 A 가 있는데 Redis 에는 없다
        assertThat(bRequest).isEqualTo("PUBLISHED");
        assertThat(afterB.issueRows()).isEqualTo(1);
        assertThat(aAgain).isEqualTo("PUBLISHED");
        assertThat(afterA.dbOnlyMembers()).isZero();
        assertThat(redisRepository.members(couponId)).containsExactly(a);

        append("""
                ## 구독자 2개, 그중 하나가 DB 확정 실패 (재고 1)

                - A 요청: %s → 구독자 처리 결과 %s. DB 발급 행 %d, Redis %s (DB 에만 있는 회원 %d)
                - B 요청: %s → 구독자 처리 결과 누적 %s. DB 발급 행 %d, Redis %s
                - A 재요청: %s → 구독자 처리 결과 누적 %s. DB 발급 행 %d, Redis %s

                """.formatted(aFirst, splitResults, split.issueRows(), redisAfterSplit, split.dbOnlyMembers(),
                bRequest, resultsAfterB, afterB.issueRows(), redisAfterB, aAgain, delta(before), afterA.issueRows(), redisRepository.members(couponId)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            구독자 1, 재고보다 많은 요청 | 1 | 10 | 50 | 1
            구독자 1, 재고 충분           | 1 | 50 | 50 | 1
            구독자 1, 동일 회원 중복      | 1 | 50 | 10 | 5
            구독자 2, 재고 충분           | 2 | 50 | 50 | 1
            """)
    void concurrentRequests(String scenario, int subscriberCount, int totalCount, int memberCount, int requestsPerMember) throws Exception {
        for (int i = 0; i < subscriberCount; i++) {
            subscribe();
        }
        for (int round = 1; round <= 3; round++) {
            Map<String, Long> before = consumer.results();
            Burst accepted = burst(CouponIssueStrategy.REDIS_PUBSUB, true, totalCount, memberCount, requestsPerMember, round);
            long settleStart = System.nanoTime();
            long deliveries = accepted.result().publishReceivers().entrySet().stream().mapToLong(e -> e.getKey() * e.getValue()).sum();
            awaitProcessed(before, deliveries);
            long settleMs = (System.nanoTime() - settleStart) / 1_000_000;
            LabResult r = labService.result(accepted.result().couponId());
            Map<String, Long> results = delta(before);

            assertThat(r.checks().atMostOncePerMember()).isTrue();
            assertThat(r.checks().notOverIssued()).isTrue();
            assertThat(r.checks().issuedCountMatchesRows()).isTrue();
            assertThat(r.issueRows()).isEqualTo(Math.min(totalCount, memberCount));
            assertThat(r.publishReceivers()).isEqualTo(Map.of((long) subscriberCount, r.requests().get(IssueOutcome.PUBLISHED)));
            assertThat(results.get("SUCCESS")).isEqualTo(r.issueRows());
            assertThat(r.redisOnlyMembers()).isZero();
            assertThat(r.dbOnlyMembers()).isZero();

            if (round == 1) {
                append("\n## 동시 요청: REDIS_PUBSUB, " + scenario + " (재고 " + r.totalCount() + ", 요청 " + memberCount * requestsPerMember
                        + ", 요청 스레드 " + memberCount * requestsPerMember + ", 구독자 작업 스레드 " + executor.getCorePoolSize() + ")\n\n"
                        + "| 회차 | 접수 결과 | PUBLISH 받은 구독자 수 | 구독자 처리 결과 | 발급 행 | issued_count | Redis 에만 / DB 에만 | 먼저 온 N명 중 당첨 | 접수 p50/p95/p99 ms | 접수 총 소요 ms | 접수 뒤 처리 끝까지 ms |\n"
                        + "|---:|---|---|---|---:|---:|---|---:|---|---:|---:|\n");
            }
            long n = Math.min(r.totalCount(), r.ordering().validRequests());
            append("| %d | %s | %s | %s | %d | %d | %d / %d | %d/%d | %.0f/%.0f/%.0f | %d | %d |%n".formatted(
                    round, r.requests(), r.publishReceivers(), results, r.issueRows(), r.issuedCount(),
                    r.redisOnlyMembers(), r.dbOnlyMembers(), r.ordering().firstNValidWon(), n,
                    r.latencyMs().p50(), r.latencyMs().p95(), r.latencyMs().p99(), accepted.wallMs(), settleMs));
        }
    }

    private void subscribe() throws Exception {
        long expected = subscriberCount() + 1;
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(executor);
        container.addMessageListener(consumer, new ChannelTopic(PubSubIssueCouponService.CHANNEL));
        container.afterPropertiesSet();
        container.start();
        subscribers.add(container);
        await().atMost(Duration.ofSeconds(10)).until(() -> subscriberCount() == expected);
    }

    // PUBSUB NUMSUB: 지금 채널을 구독 중인 연결 수
    @SuppressWarnings("unchecked")
    private long subscriberCount() throws Exception {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            RedisClusterAsyncCommands<byte[], byte[]> commands = (RedisClusterAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
            return commands.pubsubNumsub(PubSubIssueCouponService.CHANNEL.getBytes(StandardCharsets.UTF_8)).get()
                    .values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private String issue(Long couponId, long memberId) {
        try {
            labService.issue(couponId, memberId, CouponIssueStrategy.REDIS_PUBSUB);
            return "PUBLISHED";
        } catch (RuntimeException e) {
            return IssueOutcome.of(e).name();
        }
    }

    // 구독자 처리 결과가 before 이후 expected 건 쌓이고 작업 스레드가 모두 쉴 때까지 기다린다
    private void awaitProcessed(Map<String, Long> before, long expected) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(10)).until(() -> delta(before).values().stream().mapToLong(Long::longValue).sum() >= expected);
        awaitIdle();
    }

    private void awaitIdle() {
        await().atMost(Duration.ofSeconds(30)).until(() -> executor.getActiveCount() == 0 && executor.getQueueSize() == 0);
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
