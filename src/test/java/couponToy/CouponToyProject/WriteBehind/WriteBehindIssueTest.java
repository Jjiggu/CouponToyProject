package couponToy.CouponToyProject.WriteBehind;

import couponToy.CouponToyProject.CouponIssue.service.CouponIssueStrategy;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader;
import couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader.Reconciliation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.GROUP;
import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.STREAM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9단계 write-behind: 요청은 Redis(Lua 로 판단·예약·적재 요청 기록)에만 쓰고, 적재기가 여러 건을 한 트랜잭션에 INSERT 만 한다.
 * issued_count 와 누락분은 대사(reconcile)가 나중에 맞춘다. 적재기는 테스트가 loadOnce() 를 직접 부른다(앱에서는 wb-loader 프로필 프로세스).
 * 실행 결과는 build/lab/stage9-write-behind.md 에 쌓인다.
 */
@SpringBootTest
class WriteBehindIssueTest extends ConcurrencyExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage9-write-behind.md");

    @Autowired
    private WriteBehindLoader loader;

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "9단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    // 스트림은 쿠폰별 키가 아니라 하나라서, 테스트마다 비우고 그룹을 새로 만든다
    @BeforeEach
    void emptyStream() {
        redisTemplate.delete(STREAM);
        loader.ensureGroup();
    }

    @Test
    void 요청은_Redis_에만_남고_적재기는_INSERT_만_하며_issued_count_는_대사가_맞춘다() throws Exception {
        Burst burst = burst(CouponIssueStrategy.REDIS_WRITE_BEHIND, true, 10, 20, 2, 9);
        LabResult accepted = burst.result();
        Long couponId = accepted.couponId();

        int firstBatch = loader.loadOnce();
        int rest = drain();
        LabResult loaded = labService.result(couponId);
        Reconciliation reconciled = loader.reconcile(couponId);
        LabResult after = labService.result(couponId);

        assertThat(accepted.requests().get(IssueOutcome.QUEUED)).isEqualTo(10);
        assertThat(accepted.issueRows()).isZero();
        assertThat(accepted.redisOnlyMembers()).isEqualTo(10);
        assertThat(firstBatch).isEqualTo(10); // 한 번에 읽어 한 트랜잭션으로 넣었다
        assertThat(rest).isZero();
        assertThat(loaded.issueRows()).isEqualTo(10);
        assertThat(loaded.issuedCount()).isZero(); // 적재기는 쿠폰 행을 건드리지 않는다
        assertThat(loaded.checks().issuedCountMatchesRows()).isFalse();
        assertThat(reconciled.insertedFromRedis()).isEmpty();
        assertThat(reconciled.dbOnlyMembers()).isEmpty();
        assertThat(after.issuedCount()).isEqualTo(10);
        assertThat(after.checks().atMostOncePerMember()).isTrue();
        assertThat(after.checks().notOverIssued()).isTrue();
        assertThat(after.checks().issuedCountMatchesRows()).isTrue();
        assertThat(after.checks().fullyIssued()).isTrue();

        append("""
                ## 정상: 재고 10, 회원 20명이 두 번씩 (요청 40, 요청 스레드 40)

                - 접수 직후: 요청 결과 %s, DB 발급 행 %d, Redis 에만 있는 회원 %d, 접수 p95 %.1fms
                - 적재기 한 번: %d 건을 한 트랜잭션으로 INSERT. DB 발급 행 %d, issued_count %d (쿠폰 행은 그대로)
                - 대사: Redis 에만 있던 회원 %s, DB 에만 있는 회원 %s → issued_count %d, 검증 %s

                """.formatted(accepted.requests(), accepted.issueRows(), accepted.redisOnlyMembers(), accepted.latencyMs().p95(),
                firstBatch, loaded.issueRows(), loaded.issuedCount(),
                reconciled.insertedFromRedis(), reconciled.dbOnlyMembers(), after.issuedCount(), after.checks()));
    }

    @Test
    void DB_커밋_뒤_ACK_전에_죽어_같은_메시지를_다시_적재해도_행은_한_번만_남는다() {
        Long couponId = labCoupon(3);
        long first = labService.createMembers(3).firstMemberId();
        LongStream.range(first, first + 3).forEach(m -> labService.issue(couponId, m, CouponIssueStrategy.REDIS_WRITE_BEHIND, true));

        // 적재기가 읽고(pending) 커밋까지 했는데 ACK 전에 죽은 상태를 만든다
        redisTemplate.opsForStream().read(Consumer.from(GROUP, "loader"), StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        LongStream.range(first, first + 3).forEach(m -> jdbcTemplate.update(
                "INSERT INTO coupon_issues (coupon_id, member_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", couponId, m));
        long pendingBefore = pending();

        int reloaded = drain();
        LabResult r = labService.result(couponId);

        assertThat(pendingBefore).isEqualTo(3);
        assertThat(reloaded).isEqualTo(3);
        assertThat(pending()).isZero();
        assertThat(r.issueRows()).isEqualTo(3);
        assertThat(r.checks().atMostOncePerMember()).isTrue();

        append("""
                ## 커밋 뒤 ACK 전 종료 (재고 3)

                - 다시 시작한 적재기가 pending %d 건을 다시 읽어 INSERT: 이미 있는 행이라 바뀐 것 없음. DB 발급 행 %d, pending %d

                """.formatted(pendingBefore, r.issueRows(), pending()));
    }

    @Test
    void 적재_요청이_사라져도_대사가_Redis_명단으로_DB_를_채운다() {
        Long couponId = labCoupon(5);
        long first = labService.createMembers(5).firstMemberId();
        LongStream.range(first, first + 5).forEach(m -> labService.issue(couponId, m, CouponIssueStrategy.REDIS_WRITE_BEHIND, true));

        redisTemplate.opsForStream().trim(STREAM, 0); // 적재 요청 기록만 잃은 경우
        int loaded = drain();
        LabResult missing = labService.result(couponId);
        Reconciliation reconciled = loader.reconcile(couponId);
        LabResult after = labService.result(couponId);

        assertThat(loaded).isZero();
        assertThat(missing.issueRows()).isZero();
        assertThat(missing.redisOnlyMembers()).isEqualTo(5);
        assertThat(reconciled.insertedFromRedis()).hasSize(5);
        assertThat(after.issueRows()).isEqualTo(5);
        assertThat(after.issuedCount()).isEqualTo(5);
        assertThat(after.redisOnlyMembers()).isZero();

        append("""
                ## 적재 요청 기록 유실 (재고 5)

                - 스트림을 비운 뒤 적재기: %d 건. DB 발급 행 %d, Redis 에만 있는 회원 %d
                - 대사: Redis 명단에서 %d 명을 DB 에 넣음 → DB 발급 행 %d, issued_count %d

                """.formatted(loaded, missing.issueRows(), missing.redisOnlyMembers(),
                reconciled.insertedFromRedis().size(), after.issueRows(), after.issuedCount()));
    }

    @Test
    void Redis_가_명단을_잃으면_DB_에_재고보다_많이_쌓이고_대사는_알려주기만_한다() {
        Long couponId = labCoupon(5);
        long first = labService.createMembers(10).firstMemberId();
        LongStream.range(first, first + 5).forEach(m -> labService.issue(couponId, m, CouponIssueStrategy.REDIS_WRITE_BEHIND, true));
        drain();

        redisTemplate.delete("coupon:issued:" + couponId); // 영속화 없이 Redis 가 재시작된 경우
        LongStream.range(first + 5, first + 10).forEach(m -> labService.issue(couponId, m, CouponIssueStrategy.REDIS_WRITE_BEHIND, true));
        drain();
        LabResult r = labService.result(couponId);
        Reconciliation reconciled = loader.reconcile(couponId);

        assertThat(r.requests().get(IssueOutcome.QUEUED)).isEqualTo(10);
        assertThat(r.issueRows()).isEqualTo(10);
        assertThat(r.checks().notOverIssued()).isFalse();
        assertThat(reconciled.overIssued()).isTrue();
        assertThat(reconciled.dbOnlyMembers()).containsExactlyElementsOf(LongStream.range(first, first + 5).boxed().toList());

        append("""
                ## Redis 명단 유실 뒤 계속 요청 (재고 5)

                - 5명 발급·적재 뒤 Redis 명단을 지우고 다른 5명 요청: 요청 결과 %s
                - DB 발급 행 %d (재고 %d), 재고 초과 %s. DB 에는 수량 검사가 없어 막지 못한다
                - 대사: DB 에만 있는 회원 %s, issued_count %d, 재고 초과 %s (되돌리지 않고 알려만 준다)

                """.formatted(r.requests(), r.issueRows(), r.totalCount(), !r.checks().notOverIssued(),
                reconciled.dbOnlyMembers(), reconciled.issuedCount(), reconciled.overIssued()));
    }

    private int drain() {
        int total = 0, n;
        while ((n = loader.loadOnce()) > 0) {
            total += n;
        }
        return total;
    }

    private long pending() {
        return redisTemplate.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages();
    }
}
