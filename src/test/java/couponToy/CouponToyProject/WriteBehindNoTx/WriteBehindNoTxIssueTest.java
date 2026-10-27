package couponToy.CouponToyProject.WriteBehindNoTx;

import couponToy.CouponToyProject.CouponIssue.service.CouponIssueStrategy;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader;
import couponToy.CouponToyProject.WriteBehindNoTx.repository.MemberExistsRepository;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.STREAM;
import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader.DEAD_LETTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 10단계: 9단계 write-behind 에서 회원 확인만 트랜잭션 없이(SELECT 한 문장) 하도록 바꾼 것.
 * 실행 결과는 build/lab/stage10-no-tx.md 에 쌓인다. 처리량은 EC2 에서 따로 잰다(docs/learning/10 문서).
 *
 * 테스트가 다루는 것
 *   1. 설정 문장이 정말 사라졌는지: 9단계 경로(existsById)와 10단계 경로(MemberExistsRepository)를 같은 횟수로 불러
 *      MySQL 의 문장 카운터(SHOW GLOBAL STATUS 의 Com_*) 증가를 비교한다
 *   2. 정상 흐름: 접수 → 적재 → 대사가 9단계와 똑같이 맞는지(회원 확인 말고는 같은 코드를 쓰므로)
 *   3. 없는 회원: 회원 확인이 여전히 막고, Redis 에는 아무것도 남지 않는지
 */
@SpringBootTest
class WriteBehindNoTxIssueTest extends ConcurrencyExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage10-no-tx.md");
    // 1번 테스트에서 각 경로를 부르는 횟수
    private static final int CALLS = 200;

    @Autowired
    private WriteBehindLoader loader;

    @Autowired
    private MemberExistsRepository memberExistsRepository;

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "10단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    // 9단계와 같은 스트림을 쓰므로, 앞 테스트(9단계 테스트 포함)의 메시지·pending 이 섞이지 않게 테스트마다 비우고 그룹을 새로 만든다
    @BeforeEach
    void emptyStream() {
        redisTemplate.delete(List.of(STREAM, DEAD_LETTER));
        loader.ensureGroup();
    }

    @Test
    void 회원_확인을_트랜잭션_없이_하면_SET_문장과_COMMIT_이_나가지_않는다() {
        long first = labService.createMembers(CALLS).firstMemberId();

        // 9단계 경로: SimpleJpaRepository.existsById → 읽기 전용 트랜잭션 → SET 문장들 + SELECT + COMMIT
        Map<String, Long> nineStage = countStatements(m -> memberRepository.existsById(m), first);
        // 10단계 경로: MemberExistsRepository.countByMemberId (SUPPORTS, 바깥 트랜잭션 없음) → SELECT 만
        Map<String, Long> tenStage = countStatements(m -> memberExistsRepository.countByMemberId(m), first);

        // 9단계: 요청 하나에 SET 이 4번(autocommit 0/1, 세션 READ ONLY/READ WRITE), COMMIT 1번, SELECT 1번
        assertThat(nineStage.get("Com_set_option")).isEqualTo(4L * CALLS);
        assertThat(nineStage.get("Com_commit")).isEqualTo(CALLS);
        assertThat(nineStage.get("Com_select")).isEqualTo(CALLS);
        // 10단계: SET 도 COMMIT 도 없고 SELECT 만 요청 수만큼
        assertThat(tenStage.get("Com_set_option")).isZero();
        assertThat(tenStage.get("Com_commit")).isZero();
        assertThat(tenStage.get("Com_select")).isEqualTo(CALLS);

        append("""
                ## 회원 확인 %d 번에 늘어난 MySQL 문장 카운터 (SHOW GLOBAL STATUS)

                | 경로 | Com_set_option | Com_commit | Com_select |
                |---|---:|---:|---:|
                | 9단계 existsById (읽기 전용 트랜잭션) | %d | %d | %d |
                | 10단계 countByMemberId (SUPPORTS, 트랜잭션 없음) | %d | %d | %d |

                - Com_set_option 은 SET 문장 수다. 9단계는 요청 하나에 %d 번: SET autocommit 2번에 SET SESSION TRANSACTION READ ONLY / READ WRITE 2번까지 세어진다
                - 카운터를 읽는 SHOW GLOBAL STATUS 는 Com_show_status 로 세어져 위 세 값에는 섞이지 않는다

                """.formatted(CALLS,
                nineStage.get("Com_set_option"), nineStage.get("Com_commit"), nineStage.get("Com_select"),
                tenStage.get("Com_set_option"), tenStage.get("Com_commit"), tenStage.get("Com_select"),
                nineStage.get("Com_set_option") / CALLS));
    }

    @Test
    void 정상_흐름은_9단계와_같다_접수_적재_대사() throws Exception {
        // 재고 10, 회원 20명이 두 번씩(요청 40건)을 동시에 보낸다. 적재기는 아직 부르지 않았다
        Burst burst = burst(CouponIssueStrategy.REDIS_WRITE_BEHIND_NO_TX, true, 10, 20, 2, 10);
        LabResult accepted = burst.result();
        Long couponId = accepted.couponId();

        // 9단계와 같은 적재기·같은 스트림이 그대로 처리한다
        int loaded = drain();
        LabResult beforeReconcile = labService.result(couponId);
        loader.reconcile(couponId);
        LabResult after = labService.result(couponId);

        // 접수 직후: 재고만큼 접수, DB 는 비어 있다
        assertThat(accepted.requests().get(IssueOutcome.QUEUED)).isEqualTo(10);
        assertThat(accepted.issueRows()).isZero();
        // 적재 뒤: 10행. issued_count 는 대사 전이라 아직 0
        assertThat(loaded).isEqualTo(10);
        assertThat(beforeReconcile.issueRows()).isEqualTo(10);
        assertThat(beforeReconcile.issuedCount()).isZero();
        // 대사 뒤: 검증 네 가지 모두 참
        assertThat(after.checks().atMostOncePerMember()).isTrue();
        assertThat(after.checks().notOverIssued()).isTrue();
        assertThat(after.checks().issuedCountMatchesRows()).isTrue();
        assertThat(after.checks().fullyIssued()).isTrue();

        append("""
                ## 정상: 재고 10, 회원 20명이 두 번씩 (요청 40, 요청 스레드 40)

                - 접수 직후: 요청 결과 %s, DB 발급 행 %d, 접수 p95 %.1fms
                - 적재기: %d 건 적재, DB 발급 행 %d, issued_count %d (대사 전)
                - 대사 뒤: issued_count %d, 검증 %s

                """.formatted(accepted.requests(), accepted.issueRows(), accepted.latencyMs().p95(),
                loaded, beforeReconcile.issueRows(), beforeReconcile.issuedCount(), after.issuedCount(), after.checks()));
    }

    @Test
    void 없는_회원은_트랜잭션_없이도_막히고_Redis_에_아무것도_남지_않는다() {
        Long couponId = labCoupon(1);
        // 존재하지 않는 회원 번호: 지금까지 만든 가장 큰 번호보다 크게
        long missing = labService.createMembers(1).firstMemberId() + 1_000_000;

        assertThatThrownBy(() -> labService.issue(couponId, missing, CouponIssueStrategy.REDIS_WRITE_BEHIND_NO_TX, true))
                .isInstanceOf(MemberNotFoundException.class);
        // 회원 확인이 Lua 보다 먼저라, 예약(SADD)도 적재 요청(XADD)도 생기지 않았다
        assertThat(redisTemplate.opsForSet().size("coupon:issued:" + couponId)).isZero();
        assertThat(redisTemplate.opsForStream().size(STREAM)).isZero();

        append("""
                ## 없는 회원 (재고 1)

                - 회원 %d 요청: MemberNotFoundException. Redis 명단 0, 스트림 0

                """.formatted(missing));
    }

    // first 부터 CALLS 명의 회원을 check 로 하나씩 확인하고, 그동안 늘어난 MySQL 문장 카운터를 돌려준다.
    // SHOW GLOBAL STATUS 는 서버 전체 값이라 다른 연결의 문장도 섞일 수 있다. 테스트 중에는 이 연결들만 MySQL 을 쓴다
    private Map<String, Long> countStatements(LongConsumer check, long first) {
        Map<String, Long> before = statementCounters();
        LongStream.range(first, first + CALLS).forEach(check);
        Map<String, Long> after = statementCounters();
        return after.keySet().stream().collect(Collectors.toMap(k -> k, k -> after.get(k) - before.get(k)));
    }

    // SHOW GLOBAL STATUS 의 Com_set_option(SET 문장), Com_commit(COMMIT 문장), Com_select(SELECT 문장)
    private Map<String, Long> statementCounters() {
        return jdbcTemplate.query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_set_option', 'Com_commit', 'Com_select')",
                        (rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // 읽을 것이 없을 때까지 적재기를 부른다
    private int drain() {
        int total = 0, n;
        while ((n = loader.loadOnce()) > 0) {
            total += n;
        }
        return total;
    }
}
