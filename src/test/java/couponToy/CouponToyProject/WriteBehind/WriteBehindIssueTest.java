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
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.GROUP;
import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader.DEAD_LETTER;
import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.STREAM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9단계 write-behind: 요청은 Redis(Lua 로 판단·예약·적재 요청 기록)에만 쓰고, 적재기가 여러 건을 한 트랜잭션에 INSERT 만 한다.
 * issued_count 와 누락분은 대사(reconcile)가 나중에 맞춘다. 적재기는 테스트가 loadOnce() 를 직접 부른다(앱에서는 wb-loader 프로필 프로세스).
 * 실행 결과는 build/lab/stage9-write-behind.md 에 쌓인다.
 *
 * 테스트가 다루는 것
 *   1. 정상: 접수 직후 DB 는 비어 있고, 적재기 한 번에 묶음으로 들어가며, issued_count 는 대사 때만 맞는다
 *   2. 커밋 뒤 ACK 전 종료: 같은 메시지를 다시 적재해도 행은 하나(ON DUPLICATE KEY)
 *   3. 적재 요청(스트림 메시지) 유실: 적재기는 넣을 것이 없지만, 대사가 Redis 명단으로 채운다
 *   4. Redis 명단 유실: DB 에 수량 검사가 없어 재고보다 많이 쌓이고, 대사는 알려 주기만 한다
 *   5. 계속 실패하는 메시지: 나머지는 바로 들어가고, 문제 메시지만 전달 횟수 상한 뒤 따로 보관된다. 대사가 나중에 채운다
 * 실제 적재 루프(1초 쉬고 다시, 20ms 쉬고 다시)는 돌리지 않는다. 테스트는 loadOnce() 를 원하는 만큼 직접 불러 단계를 하나씩 본다
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

    // 스트림은 쿠폰별 키가 아니라 하나라서, 테스트마다 비우고 그룹을 새로 만든다.
    // 앞 테스트의 메시지·pending·따로 보관한 메시지가 남아 있으면 loadOnce() 결과와 전달 횟수가 섞인다
    @BeforeEach
    void emptyStream() {
        redisTemplate.delete(List.of(STREAM, DEAD_LETTER));
        loader.ensureGroup();
    }

    @Test
    void 요청은_Redis_에만_남고_적재기는_INSERT_만_하며_issued_count_는_대사가_맞춘다() throws Exception {
        // 재고 10, 회원 20명이 두 번씩(요청 40건)을 스레드 40개로 한꺼번에 보낸다(래치로 동시에 출발).
        // 적재기는 아직 부르지 않았으므로 이 시점의 결과는 "접수 직후" 상태다
        Burst burst = burst(CouponIssueStrategy.REDIS_WRITE_BEHIND, true, 10, 20, 2, 9);
        LabResult accepted = burst.result();
        Long couponId = accepted.couponId();

        // 적재기 한 번: 스트림에 쌓인 10건을 한 번에 읽어 한 트랜잭션으로 넣고 ACK 한다(묶음 최대 100)
        int firstBatch = loader.loadOnce();
        // 더 읽을 것이 없는지 확인. 0 이면 한 번에 다 넣은 것이다
        int rest = drain();
        // 적재 뒤, 대사 전: 행은 있지만 issued_count 는 아직 0
        LabResult loaded = labService.result(couponId);
        // 대사: Redis 명단과 DB 행이 이미 같으므로 넣을 회원은 없고, issued_count 만 행 수로 맞춘다
        Reconciliation reconciled = loader.reconcile(couponId);
        LabResult after = labService.result(couponId);

        // 접수 직후: 재고만큼만 접수(나머지는 품절·중복 409), DB 에는 아직 아무것도 없다
        assertThat(accepted.requests().get(IssueOutcome.QUEUED)).isEqualTo(10);
        assertThat(accepted.issueRows()).isZero();
        assertThat(accepted.redisOnlyMembers()).isEqualTo(10);
        assertThat(firstBatch).isEqualTo(10); // 한 번에 읽어 한 트랜잭션으로 넣었다
        assertThat(rest).isZero();
        assertThat(loaded.issueRows()).isEqualTo(10);
        assertThat(loaded.issuedCount()).isZero(); // 적재기는 쿠폰 행을 건드리지 않는다
        assertThat(loaded.checks().issuedCountMatchesRows()).isFalse(); // 대사 전에는 이 검증이 거짓인 것이 정상이다
        // 대사 뒤: 검증 네 가지가 모두 참
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
        // 1) 적재기와 같은 소비자 이름(loader)으로 새 메시지를 읽기만 한다 → 세 건이 loader 의 pending 에 올라간다
        redisTemplate.opsForStream().read(Consumer.from(GROUP, "loader"), StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        // 2) 그 세 건이 DB 에 커밋된 것처럼 행을 직접 넣는다. ACK 는 하지 않는다(여기서 죽었다고 본다)
        LongStream.range(first, first + 3).forEach(m -> jdbcTemplate.update(
                "INSERT INTO coupon_issues (coupon_id, member_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", couponId, m));
        long pendingBefore = pending();

        // 3) 다시 뜬 적재기: read("0") 으로 자기 pending 세 건을 다시 받아 INSERT 한다.
        //    이미 있는 행이라 ON DUPLICATE KEY 가 아무것도 바꾸지 않고, 커밋 뒤 ACK 해서 pending 이 빈다
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

        // 적재 요청 기록만 잃은 경우: 스트림을 0 건으로 자른다(XTRIM MAXLEN 0). Redis 명단(coupon:issued:{id})은 그대로다
        redisTemplate.opsForStream().trim(STREAM, 0);
        // 적재기는 읽을 메시지가 없어 아무것도 넣지 못한다
        int loaded = drain();
        LabResult missing = labService.result(couponId);
        // 대사: 기준인 Redis 명단에는 다섯 명이 있고 DB 에는 없으므로 다섯 명을 넣는다
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
        // 재고 5 를 다섯 명이 다 받고 DB 까지 적재된 상태
        LongStream.range(first, first + 5).forEach(m -> labService.issue(couponId, m, CouponIssueStrategy.REDIS_WRITE_BEHIND, true));
        drain();

        // 영속화 없이 Redis 가 재시작된 경우: 발급 회원 명단이 사라진다(재고 수 coupon:total 은 DB 에서 다시 읽힌다)
        redisTemplate.delete("coupon:issued:" + couponId);
        // 명단이 비었으니 Lua 는 재고가 남았다고 판단해 다른 다섯 명도 접수하고, 적재기는 수량 확인 없이 INSERT 만 한다
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

    @Test
    void 계속_실패하는_메시지는_따로_보관하고_나머지는_막히지_않으며_대사가_나중에_채운다() {
        Long couponId = labCoupon(3);
        long a = labService.createMembers(3).firstMemberId(), b = a + 1, c = a + 2;
        // DB 가 B 의 행만 거절하게 만든다(테스트 DB 에만 잠깐 거는 트리거). 실제로는 데이터 오류처럼 다시 해도 계속 실패하는 경우다.
        // B 를 넣는 INSERT 는 SQL state 45000 으로 실패하고, 같은 문장에 A·C 가 함께 있으면 문장 전체가 실패한다
        jdbcTemplate.execute("CREATE TRIGGER wb_reject_member BEFORE INSERT ON coupon_issues FOR EACH ROW BEGIN "
                + "IF NEW.member_id = " + b + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'rejected for test'; END IF; END");
        long rowsAfterFirst;
        int failedCalls = 0;
        try {
            LongStream.of(a, b, c).forEach(m -> labService.issue(couponId, m, CouponIssueStrategy.REDIS_WRITE_BEHIND, true));
            // 1번째 호출: A·B·C 묶음 INSERT 실패 → 한 건씩: A 성공(ACK), B 실패(전달 1회, 남김), C 성공(ACK) → 예외
            failedCalls += loadOnceFailed() ? 1 : 0;
            rowsAfterFirst = labService.result(couponId).issueRows();
            // 2·3번째 호출: pending 의 B 만 다시 받아(전달 2, 3회) 실패 → 예외
            // 4번째 호출: B 전달 4회 > 상한 3 → 따로 보관하고 ACK → 예외 없이 끝나 루프를 빠져나온다
            while (loadOnceFailed()) {
                failedCalls++;
            }
        } finally {
            // 테스트가 중간에 실패해도 트리거는 꼭 지운다. 남으면 다른 테스트의 INSERT 가 영향을 받는다
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS wb_reject_member");
        }
        LabResult loaded = labService.result(couponId);
        // 따로 보관한 스트림을 처음부터 끝까지 읽는다(XRANGE coupon:wb:stream:dead - +)
        List<MapRecord<String, Object, Object>> dead = redisTemplate.opsForStream().range(DEAD_LETTER, org.springframework.data.domain.Range.unbounded());
        Map<Object, Object> deadBody = dead.get(0).getValue();
        // 트리거를 지운 뒤 대사: B 는 Redis 명단에 있고 DB 에 없으므로 이번에는 들어간다
        Reconciliation reconciled = loader.reconcile(couponId);
        LabResult after = labService.result(couponId);

        assertThat(rowsAfterFirst).isEqualTo(2); // 첫 시도에서 A, C 는 들어갔다
        assertThat(failedCalls).isEqualTo(3);    // B 를 세 번 받고 실패, 네 번째에 따로 보관
        assertThat(pending()).isZero();
        assertThat(dead).hasSize(1);
        assertThat(deadBody.get("memberId")).isEqualTo(String.valueOf(b));
        assertThat(deadBody.get("deliveries")).isEqualTo("4");
        assertThat(loaded.issueRows()).isEqualTo(2);
        assertThat(loaded.redisOnlyMembers()).isEqualTo(1); // 예약은 되돌리지 않았다
        assertThat(reconciled.insertedFromRedis()).containsExactly(b);
        assertThat(after.issueRows()).isEqualTo(3);
        assertThat(after.issuedCount()).isEqualTo(3);

        append("""
                ## 계속 실패하는 메시지 (재고 3, DB 가 B 의 INSERT 만 거절)

                - 첫 적재: 묶음 실패 → 한 건씩 다시 넣어 A, C 적재(DB 발급 행 %d), B 는 pending
                - 적재가 실패로 끝난 횟수 %d, 그 다음 호출에서 B 를 따로 보관: %s
                - 그 뒤 pending %d, DB 발급 행 %d, Redis 에만 있는 회원 %d (예약은 그대로)
                - 거절을 풀고 대사: Redis 명단에서 %s 를 넣음 → DB 발급 행 %d, issued_count %d

                """.formatted(rowsAfterFirst, failedCalls, deadBody, pending(), loaded.issueRows(), loaded.redisOnlyMembers(),
                reconciled.insertedFromRedis(), after.issueRows(), after.issuedCount()));
    }

    // 적재기 루프처럼 부른다. 남은 실패가 있으면 loadOnce 가 예외를 던진다.
    // 실제 루프는 예외 뒤 1초 쉬지만, 테스트는 쉬지 않고 바로 다시 부른다(전달 횟수는 부를 때마다 1 씩 는다)
    private boolean loadOnceFailed() {
        try {
            loader.loadOnce();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    // 읽을 것이 없을 때까지 loadOnce() 를 부르고, 처리한 메시지 수를 더해 돌려준다(적재기가 밀린 것을 다 처리한 상태)
    private int drain() {
        int total = 0, n;
        while ((n = loader.loadOnce()) > 0) {
            total += n;
        }
        return total;
    }

    // 그룹 전체 pending 수(XPENDING coupon:wb:stream coupon-wb-loaders 요약). 0 이면 받은 메시지를 모두 ACK 했다
    private long pending() {
        return redisTemplate.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages();
    }
}
