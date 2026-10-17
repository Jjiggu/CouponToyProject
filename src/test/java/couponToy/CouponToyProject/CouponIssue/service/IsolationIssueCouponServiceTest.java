package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.support.IsolationExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1단계: 명시적 락 없이 READ COMMITTED, REPEATABLE READ, SERIALIZABLE 비교.
 * duplicateCheck=false 는 중복 확인 SELECT 를 뺀 변형이다.
 * 실행 결과는 build/lab/stage1-isolation.md 에 쌓인다.
 */
@SpringBootTest
class IsolationIssueCouponServiceTest extends IsolationExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage1-isolation.md");

    @Autowired
    private IsolationIssueCouponService isolationIssueCouponService;

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "1단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    @Test
    @DisplayName("트랜잭션은 프록시를 통해 시작된다")
    void serviceIsProxied() {
        assertThat(AopUtils.isAopProxy(isolationIssueCouponService)).isTrue();
    }

    // ---------------------------------------------------------------
    // 1. 실행 순서 고정: 두 트랜잭션이 모두 쿠폰을 읽은 뒤에야 쓰기를 시작한다.
    //    대기 락 = 쿠폰 행 잠금을 풀기 직전 "테이블:락 모드" 목록, 데드락 대기 = 데드락 로그에서 기다리던 락의 테이블.인덱스
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}, 중복 확인 {1}, 같은 회원 {2}, 재고 {3}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식          | 중복확인 | 같은회원 | 재고 | 결과                      | 행 | issued_count | 대기 락                                 | 데드락 대기
            REPEATABLE_READ | true     | false    | 1    | SUCCESS+SUCCESS           | 2  | 1            | coupons:X,REC_NOT_GAP                   | -
            REPEATABLE_READ | true     | false    | 10   | SUCCESS+SUCCESS           | 2  | 1            | coupons:X,REC_NOT_GAP                   | -
            REPEATABLE_READ | true     | true     | 10   | SUCCESS+UNIQUE_VIOLATION  | 1  | 1            | coupon_issues:S+coupons:X,REC_NOT_GAP   | -
            SERIALIZABLE    | true     | false    | 1    | DEADLOCK+SUCCESS          | 1  | 1            | coupons:S,REC_NOT_GAP                   | coupon_issues.uk_coupon_issue_coupon_member
            SERIALIZABLE    | true     | false    | 10   | DEADLOCK+SUCCESS          | 1  | 1            | coupons:S,REC_NOT_GAP                   | coupon_issues.uk_coupon_issue_coupon_member
            SERIALIZABLE    | true     | true     | 10   | DEADLOCK+SUCCESS          | 1  | 1            | coupons:S,REC_NOT_GAP                   | coupon_issues.uk_coupon_issue_coupon_member
            READ_COMMITTED  | true     | false    | 1    | SUCCESS+SUCCESS           | 2  | 1            | coupons:X,REC_NOT_GAP                   | -
            READ_COMMITTED  | true     | false    | 10   | SUCCESS+SUCCESS           | 2  | 1            | coupons:X,REC_NOT_GAP                   | -
            READ_COMMITTED  | true     | true     | 10   | SUCCESS+UNIQUE_VIOLATION  | 1  | 1            | coupon_issues:S+coupons:X,REC_NOT_GAP   | -
            REPEATABLE_READ | false    | false    | 1    | SUCCESS+SUCCESS           | 2  | 1            | coupons:X,REC_NOT_GAP                   | -
            REPEATABLE_READ | false    | true     | 10   | SUCCESS+UNIQUE_VIOLATION  | 1  | 1            | coupon_issues:S+coupons:X,REC_NOT_GAP   | -
            SERIALIZABLE    | false    | false    | 1    | DEADLOCK+SUCCESS          | 1  | 1            | coupons:S,REC_NOT_GAP                   | coupons.PRIMARY
            SERIALIZABLE    | false    | false    | 10   | DEADLOCK+SUCCESS          | 1  | 1            | coupons:S,REC_NOT_GAP                   | coupons.PRIMARY
            SERIALIZABLE    | false    | true     | 10   | DEADLOCK+SUCCESS          | 1  | 1            | coupons:S,REC_NOT_GAP                   | coupons.PRIMARY
            """)
    @DisplayName("두 트랜잭션이 같은 issuedCount 를 읽은 뒤 각각 쓴다")
    void bothReadBeforeEitherWrites(CouponIssueStrategy method, boolean duplicateCheck, boolean sameMember, int totalCount,
                                    String expectedOutcomes, long expectedRows, int expectedIssuedCount,
                                    String expectedWaitLocks, String expectedDeadlockWait) throws Exception {
        Long couponId = labCoupon(totalCount);
        Long first = createMember(1).getMemberId();
        Long second = sameMember ? first : createMember(2).getMemberId();

        Interleaving run = runWhileCouponRowLocked(method, duplicateCheck, couponId, List.of(first, second));
        reportInterleaving(method + (duplicateCheck ? "" : ", 중복 확인 SELECT 제거") + ", 재고 " + totalCount
                + (sameMember ? ", 같은 회원 2번" : ", 서로 다른 회원 2명"), run);

        // innodb_trx 에서 읽은 실제 격리 수준 (예: READ_COMMITTED -> "READ COMMITTED")
        assertThat(run.lockWaits()).extracting(w -> w.get("isolation")).containsOnly(method.name().replace('_', ' '));
        assertThat(run.lockWaits().stream().map(w -> w.get("locked_table") + ":" + w.get("lock_mode")).distinct().sorted())
                .containsExactly(expectedWaitLocks.split("\\+"));

        assertThat(run.outcomes().stream().map(Enum::name).sorted()).containsExactly(sortedSplit(expectedOutcomes));
        assertThat(run.issueRows()).isEqualTo(expectedRows);
        assertThat(run.issuedCount()).isEqualTo(expectedIssuedCount);

        if (!expectedDeadlockWait.equals("-")) {
            String[] tableAndIndex = expectedDeadlockWait.split("\\.");
            assertThat(run.deadlockWaitingLocks())
                    .anyMatch(line -> line.contains("index " + tableAndIndex[1] + " of table") && line.contains("`" + tableAndIndex[0] + "`"));
            // 데드락은 PessimisticLockingFailureException 계열로 올라와 HTTP 503 으로 매핑된다.
            assertThat(run.failures()).allMatch(e -> e instanceof PessimisticLockingFailureException);
        }
    }

    // ---------------------------------------------------------------
    // 2. 동시 요청: 시작 래치로 한꺼번에 출발시키고 결과만 기록한다.
    //    RR, RC 의 이상 현상은 실행마다 크기가 다르므로 위 1번 테스트로 증명하고, 여기서는 관찰값을 남긴다.
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}, 중복 확인 {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식          | 중복확인 | 시나리오           | 재고 | 회원 | 회원당 요청
            READ_COMMITTED  | true     | 재고보다 많은 요청 | 10   | 50   | 1
            REPEATABLE_READ | true     | 재고보다 많은 요청 | 10   | 50   | 1
            SERIALIZABLE    | true     | 재고보다 많은 요청 | 10   | 50   | 1
            SERIALIZABLE    | false    | 재고보다 많은 요청 | 10   | 50   | 1
            READ_COMMITTED  | true     | 재고 충분          | 50   | 50   | 1
            REPEATABLE_READ | true     | 재고 충분          | 50   | 50   | 1
            SERIALIZABLE    | true     | 재고 충분          | 50   | 50   | 1
            SERIALIZABLE    | false    | 재고 충분          | 50   | 50   | 1
            READ_COMMITTED  | true     | 동일 회원 중복     | 50   | 10   | 5
            REPEATABLE_READ | true     | 동일 회원 중복     | 50   | 10   | 5
            REPEATABLE_READ | false    | 동일 회원 중복     | 50   | 10   | 5
            SERIALIZABLE    | true     | 동일 회원 중복     | 50   | 10   | 5
            SERIALIZABLE    | false    | 동일 회원 중복     | 50   | 10   | 5
            """)
    @DisplayName("동시 요청 결과 기록")
    void concurrentRequests(CouponIssueStrategy method, boolean duplicateCheck, String scenario, int totalCount,
                            int memberCount, int requestsPerMember) throws Exception {
        int requestCount = memberCount * requestsPerMember;
        for (int round = 1; round <= 3; round++) {
            Burst burst = burst(method, duplicateCheck, totalCount, memberCount, requestsPerMember, round);
            reportBurst(method + (duplicateCheck ? "" : ", 중복 확인 SELECT 제거") + ", " + scenario, round, requestCount, burst);

            LabResult result = burst.result();
            assertThat(result.requests().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(requestCount);
            assertThat(result.requests()).doesNotContainKey(IssueOutcome.ERROR);
            assertThat(result.checks().atMostOncePerMember()).isTrue(); // UNIQUE 제약이 보장
            assertThat(result.issuedCount()).isLessThanOrEqualTo((int) result.issueRows()); // 갱신 손실은 값을 줄이기만 한다
            if (!duplicateCheck) {
                assertThat(result.requests()).doesNotContainKey(IssueOutcome.DUPLICATE); // 앱 중복 확인이 없으므로
            }
            if (method == CouponIssueStrategy.SERIALIZABLE) {
                assertThat(result.checks().notOverIssued()).isTrue();
                assertThat(result.checks().issuedCountMatchesRows()).isTrue();
            }
        }
        append("\n");
    }

    private static String[] sortedSplit(String plusJoined) {
        String[] parts = plusJoined.split("\\+");
        Arrays.sort(parts);
        return parts;
    }
}
