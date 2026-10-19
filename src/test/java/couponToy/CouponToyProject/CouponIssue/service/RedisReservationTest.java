package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3단계: Redis 에서 중복·수량을 판단해 발급 자격을 예약하는 방식 비교 (SET 명령만, MULTI/EXEC, WATCH, Lua).
 * 모든 방식은 같은 DB 확정(IssueConfirmService)을 쓴다. 실행 결과는 build/lab/stage3-redis.md 에 쌓인다.
 *
 * 프로세스 강제 종료는 실패 지점에서 Error 를 던져 흉내 낸다. 그 뒤의 코드와 보상은 실행되지 않고,
 * 커밋 전 DB 트랜잭션은 롤백되며, 이미 실행된 Redis 명령은 남는다.
 */
@SpringBootTest
class RedisReservationTest extends ConcurrencyExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage3-redis.md");

    @Autowired
    private FailurePoints failurePoints;

    @Autowired
    private CouponIssuedRedisRepository redisRepository;

    private ExecutorService executor;

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "3단계 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    @BeforeEach
    void startExecutor() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stop() {
        failurePoints.clear();
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------
    // 1. 확인과 예약 사이에 다른 요청이 끼어드는가 (재고 1)
    //    A 가 "이미 예약됨? 재고 남음?" 확인을 마친 뒤 멈추고, 그 사이 B 가 끝까지 실행된다.
    //    MULTI/EXEC 와 Lua 는 확인과 예약 사이에 다른 명령이 끼어들 틈이 없으므로 해당 없다.
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식          | A 멈추는 지점        | A 결과         | B 결과  | A 예약 직후 SCARD | A 시도 횟수
            REDIS_SET_ONLY  | SET_ONLY_BEFORE_SADD | SOLD_OUT_AT_DB | SUCCESS | 2                 | 1
            REDIS_WATCH     | WATCH_BEFORE_EXEC    | SOLD_OUT       | SUCCESS | -                 | 2
            """)
    @DisplayName("A 가 확인을 마친 뒤 B 가 끼어든다")
    void anotherRequestBetweenCheckAndReserve(CouponIssueStrategy method, Point pausePoint, String expectedA,
                                              String expectedB, String expectedScardAfterA, int expectedAttemptsA) throws Exception {
        Long couponId = labCoupon(1);
        Long a = createMember(1).getMemberId();
        Long b = createMember(2).getMemberId();

        Pause checked = arm(pausePoint, a);
        Pause reserved = arm(Point.AFTER_RESERVE, a);
        Future<String> requestA = send(couponId, a, method);
        checked.awaitReached();

        String resultB = send(couponId, b, method).get(10, SECONDS);
        checked.release();

        String scardAfterA = "-";
        if (reserved.reachedWithin(2)) { // A 도 예약에 성공한 경우에만 여기서 멈춘다
            scardAfterA = String.valueOf(redisRepository.issuedCount(couponId));
            reserved.release();
        }
        String resultA = requestA.get(10, SECONDS);
        LabResult result = labService.result(couponId);
        long attemptsA = result.attempts().histogram().keySet().stream().mapToLong(Integer::longValue).max().orElse(0);

        append("## 끼어들기: " + method + " (재고 1)\n\n"
                + "- A 는 " + pausePoint + " 에서 멈췄고, 그 사이 B 가 끝까지 실행됐다.\n"
                + "- 결과: A " + resultA + ", B " + resultB + ", A 시도 횟수 " + attemptsA + "\n"
                + "- A 예약 직후 SCARD: " + scardAfterA + " (재고 1)\n"
                + "- 최종: " + state(couponId) + "\n\n");

        assertThat(resultA).isEqualTo(expectedA);
        assertThat(resultB).isEqualTo(expectedB);
        assertThat(scardAfterA).isEqualTo(expectedScardAfterA);
        assertThat(attemptsA).isEqualTo(expectedAttemptsA);
        assertConsistent(couponId, 1);
    }

    // ---------------------------------------------------------------
    // 2. MULTI/EXEC 의 SREM 전 틈과 다른 회원의 예약 취소가 겹친다 (재고 1)
    //    A 예약(DB 확정은 실패 예정) → B 요청 → A 실패로 예약 취소 → C 요청 → B 마무리
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식           | B 결과   | C 결과   | 최종 발급
            REDIS_MULTI_EXEC | SOLD_OUT | SOLD_OUT | 0
            REDIS_LUA        | SOLD_OUT | SUCCESS  | 1
            REDIS_SET_ONLY   | SOLD_OUT | SUCCESS  | 1
            REDIS_WATCH      | SOLD_OUT | SUCCESS  | 1
            """)
    @DisplayName("다른 회원의 예약 취소와 SREM 전 틈이 겹치면 거짓 품절이 생긴다")
    void cancellationOverlapsMultiExecGap(CouponIssueStrategy method, String expectedB, String expectedC,
                                          long expectedIssued) throws Exception {
        Long couponId = labCoupon(1);
        Long a = createMember(1).getMemberId();
        Long b = createMember(2).getMemberId();
        Long c = createMember(3).getMemberId();

        Pause aReserved = arm(Point.AFTER_RESERVE, a);
        failurePoints.arm(Point.DB_BEFORE_COMMIT, a, dbFailure());
        Future<String> requestA = send(couponId, a, method);
        aReserved.awaitReached();

        Future<String> requestB;
        Pause bBeforeSrem = null;
        if (method == CouponIssueStrategy.REDIS_MULTI_EXEC) {
            bBeforeSrem = arm(Point.MULTI_EXEC_BEFORE_SREM, b);
            requestB = send(couponId, b, method);
            bBeforeSrem.awaitReached();
        } else {
            requestB = send(couponId, b, method);
            requestB.get(10, SECONDS);
        }
        Set<Long> redisWhileBPending = new TreeSet<>(redisRepository.members(couponId));

        aReserved.release();
        String resultA = requestA.get(10, SECONDS);
        Set<Long> redisAfterACancelled = new TreeSet<>(redisRepository.members(couponId));

        String resultC = send(couponId, c, method).get(10, SECONDS);
        if (bBeforeSrem != null) {
            bBeforeSrem.release();
        }
        String resultB = requestB.get(10, SECONDS);

        append("## 취소와 겹친 SREM 전 틈: " + method + " (재고 1, 회원 A=" + a + ", B=" + b + ", C=" + c + ")\n\n"
                + "- B 처리 중 Redis: " + redisWhileBPending + ", A 취소 직후 Redis: " + redisAfterACancelled + "\n"
                + "- 결과: A " + resultA + "(DB 확정 실패 주입), B " + resultB + ", C " + resultC + "\n"
                + "- 최종: " + state(couponId) + "\n\n");

        assertThat(resultA).isEqualTo("ERROR");
        assertThat(resultB).isEqualTo(expectedB);
        assertThat(resultC).isEqualTo(expectedC);
        assertThat(issueCouponRepository.countByCouponId(couponId)).isEqualTo(expectedIssued);
        assertConsistent(couponId, 1);
    }

    // ---------------------------------------------------------------
    // 3. MULTI/EXEC 의 SREM 전에 프로세스가 죽는다 (재고 1, A 는 이미 발급)
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식           | B 첫 요청 | 직후 SCARD | B 재요청
            REDIS_MULTI_EXEC | CRASHED   | 2          | DUPLICATE
            REDIS_LUA        | SOLD_OUT  | 1          | SOLD_OUT
            """)
    @DisplayName("재고 초과로 되돌리기 전에 죽으면 쿠폰 없는 회원이 Redis 에 영구히 남는다")
    void crashBeforeMultiExecSrem(CouponIssueStrategy method, String expectedFirst, long expectedScard,
                                  String expectedRetry) throws Exception {
        Long couponId = labCoupon(1);
        Long a = createMember(1).getMemberId();
        Long b = createMember(2).getMemberId();
        assertThat(send(couponId, a, method).get(10, SECONDS)).isEqualTo("SUCCESS");

        failurePoints.arm(Point.MULTI_EXEC_BEFORE_SREM, b, crash());
        String first = send(couponId, b, method).get(10, SECONDS);
        long scard = redisRepository.issuedCount(couponId);
        String retry = send(couponId, b, method).get(10, SECONDS);

        append("## SREM 전 프로세스 종료: " + method + " (재고 1, A 발급 완료 후 B 요청)\n\n"
                + "- B 첫 요청: " + first + ", 직후 SCARD: " + scard + ", B 재요청: " + retry + "\n"
                + "- 최종: " + state(couponId) + "\n\n");

        assertThat(first).isEqualTo(expectedFirst);
        assertThat(scard).isEqualTo(expectedScard);
        assertThat(retry).isEqualTo(expectedRetry);
        assertThat(issueCouponRepository.countByCouponId(couponId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // 4. Redis 예약 성공 후 DB 확정 실패 / 프로세스 종료 (모든 방식이 같은 처리를 거친다, 재고 1)
    // ---------------------------------------------------------------

    enum Failure { DB_FAILURE, CRASH_AFTER_RESERVE, CRASH_BEFORE_COMMIT }

    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식           | 실패                | A 결과  | 직후 Redis 에 A | A 재요청  | B 요청
            REDIS_SET_ONLY   | DB_FAILURE          | ERROR   | false           | SUCCESS   | SOLD_OUT
            REDIS_MULTI_EXEC | DB_FAILURE          | ERROR   | false           | SUCCESS   | SOLD_OUT
            REDIS_WATCH      | DB_FAILURE          | ERROR   | false           | SUCCESS   | SOLD_OUT
            REDIS_LUA        | DB_FAILURE          | ERROR   | false           | SUCCESS   | SOLD_OUT
            REDIS_SET_ONLY   | CRASH_AFTER_RESERVE | CRASHED | true            | DUPLICATE | SOLD_OUT
            REDIS_MULTI_EXEC | CRASH_AFTER_RESERVE | CRASHED | true            | DUPLICATE | SOLD_OUT
            REDIS_WATCH      | CRASH_AFTER_RESERVE | CRASHED | true            | DUPLICATE | SOLD_OUT
            REDIS_LUA        | CRASH_AFTER_RESERVE | CRASHED | true            | DUPLICATE | SOLD_OUT
            REDIS_LUA        | CRASH_BEFORE_COMMIT | CRASHED | true            | DUPLICATE | SOLD_OUT
            """)
    @DisplayName("Redis 예약 뒤 DB 확정 전에 실패하거나 죽는다")
    void failureAfterReservation(CouponIssueStrategy method, Failure failure, String expectedA, boolean expectedAInRedis,
                                 String expectedRetry, String expectedB) throws Exception {
        Long couponId = labCoupon(1);
        Long a = createMember(1).getMemberId();
        Long b = createMember(2).getMemberId();
        switch (failure) {
            case DB_FAILURE -> failurePoints.arm(Point.DB_BEFORE_COMMIT, a, dbFailure());
            case CRASH_AFTER_RESERVE -> failurePoints.arm(Point.AFTER_RESERVE, a, crash());
            case CRASH_BEFORE_COMMIT -> failurePoints.arm(Point.DB_BEFORE_COMMIT, a, crash());
        }

        String resultA = send(couponId, a, method).get(10, SECONDS);
        boolean aInRedis = redisRepository.members(couponId).contains(a);
        long rowsAfterA = issueCouponRepository.countByCouponId(couponId);
        String retry = send(couponId, a, method).get(10, SECONDS);
        String resultB = send(couponId, b, method).get(10, SECONDS);

        append("## 예약 후 " + failure + ": " + method + " (재고 1)\n\n"
                + "- A: " + resultA + ", 직후 Redis 에 A " + aInRedis + ", DB 행 " + rowsAfterA
                + " → A 재요청 " + retry + ", B 요청 " + resultB + "\n"
                + "- 최종: " + state(couponId) + "\n\n");

        assertThat(resultA).isEqualTo(expectedA);
        assertThat(aInRedis).isEqualTo(expectedAInRedis);
        assertThat(rowsAfterA).isZero();
        assertThat(retry).isEqualTo(expectedRetry);
        assertThat(resultB).isEqualTo(expectedB);
    }

    // ---------------------------------------------------------------
    // 5. 예약 취소(SREM) 전에 같은 회원이 다시 요청한다 (재고 1)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("DB 실패로 예약을 되돌리기 전, 같은 회원의 재요청은 쿠폰이 없는데 이미 발급으로 거절된다")
    void sameMemberRetriesBeforeCancel() throws Exception {
        CouponIssueStrategy method = CouponIssueStrategy.REDIS_LUA;
        Long couponId = labCoupon(1);
        Long a = createMember(1).getMemberId();
        Long b = createMember(2).getMemberId();

        failurePoints.arm(Point.DB_BEFORE_COMMIT, a, dbFailure());
        Pause beforeCancel = arm(Point.BEFORE_CANCEL, a);
        Future<String> first = send(couponId, a, method);
        beforeCancel.awaitReached();

        String retryWhilePending = send(couponId, a, method).get(10, SECONDS);
        String otherWhilePending = send(couponId, b, method).get(10, SECONDS);
        beforeCancel.release();
        String firstResult = first.get(10, SECONDS);
        String retryAfterCancel = send(couponId, a, method).get(10, SECONDS);

        append("## 예약 취소 전 재요청: " + method + " (재고 1)\n\n"
                + "- A 첫 요청: " + firstResult + "(DB 확정 실패 주입, SREM 전 멈춤)\n"
                + "- 멈춘 동안: A 재요청 " + retryWhilePending + ", B 요청 " + otherWhilePending + "\n"
                + "- SREM 후 A 재요청: " + retryAfterCancel + "\n"
                + "- 최종: " + state(couponId) + "\n\n");

        assertThat(firstResult).isEqualTo("ERROR");
        assertThat(retryWhilePending).isEqualTo("DUPLICATE");
        assertThat(otherWhilePending).isEqualTo("SOLD_OUT");
        assertThat(retryAfterCancel).isEqualTo("SUCCESS");
        assertConsistent(couponId, 1);
    }

    // ---------------------------------------------------------------
    // 6. Redis 가 발급 기록을 잃은 뒤 같은 회원이 다시 요청한다 (재고 10)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Redis 기록이 사라져도 DB UNIQUE 가 두 번째 발급을 막고, Redis 는 그 회원을 다시 기록한다")
    void redisLosesReservationsThenSameMemberRetries() throws Exception {
        CouponIssueStrategy method = CouponIssueStrategy.REDIS_LUA;
        Long couponId = labCoupon(10);
        Long a = createMember(1).getMemberId();
        assertThat(send(couponId, a, method).get(10, SECONDS)).isEqualTo("SUCCESS");

        redisTemplate.delete("coupon:issued:" + couponId); // Redis 데이터 유실을 흉내 낸다
        String retry = send(couponId, a, method).get(10, SECONDS);
        boolean relearned = redisRepository.members(couponId).contains(a);
        String retryAgain = send(couponId, a, method).get(10, SECONDS);

        append("## Redis 기록 유실 후 재요청: " + method + " (재고 10)\n\n"
                + "- 유실 후 A 재요청: " + retry + ", 그 뒤 Redis 에 A " + relearned + ", 한 번 더: " + retryAgain + "\n"
                + "- 최종: " + state(couponId) + "\n\n");

        assertThat(retry).isEqualTo("UNIQUE_VIOLATION");
        assertThat(relearned).isTrue();
        assertThat(retryAgain).isEqualTo("DUPLICATE");
        assertConsistent(couponId, 10);
    }

    // ---------------------------------------------------------------
    // 7. 동시 요청: 시작 래치로 한꺼번에 출발시키고 결과를 기록한다.
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # 방식           | 시나리오           | 재고 | 회원 | 회원당 요청
            REDIS_SET_ONLY   | 재고보다 많은 요청 | 10   | 50   | 1
            REDIS_MULTI_EXEC | 재고보다 많은 요청 | 10   | 50   | 1
            REDIS_WATCH      | 재고보다 많은 요청 | 10   | 50   | 1
            REDIS_LUA        | 재고보다 많은 요청 | 10   | 50   | 1
            PESSIMISTIC      | 재고보다 많은 요청 | 10   | 50   | 1
            REDIS_SET_ONLY   | 재고 충분          | 50   | 50   | 1
            REDIS_MULTI_EXEC | 재고 충분          | 50   | 50   | 1
            REDIS_WATCH      | 재고 충분          | 50   | 50   | 1
            REDIS_LUA        | 재고 충분          | 50   | 50   | 1
            PESSIMISTIC      | 재고 충분          | 50   | 50   | 1
            REDIS_SET_ONLY   | 동일 회원 중복     | 50   | 10   | 5
            REDIS_MULTI_EXEC | 동일 회원 중복     | 50   | 10   | 5
            REDIS_WATCH      | 동일 회원 중복     | 50   | 10   | 5
            REDIS_LUA        | 동일 회원 중복     | 50   | 10   | 5
            PESSIMISTIC      | 동일 회원 중복     | 50   | 10   | 5
            """)
    @DisplayName("동시 요청 결과 기록")
    void concurrentRequests(CouponIssueStrategy method, String scenario, int totalCount,
                            int memberCount, int requestsPerMember) throws Exception {
        int requestCount = memberCount * requestsPerMember;
        List<String> redisVsDb = new ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            Burst burst = burst(method, true, totalCount, memberCount, requestsPerMember, round);
            reportBurst(method + ", " + scenario, round, requestCount, burst);

            LabResult result = burst.result();
            redisVsDb.add(result.redisIssuedCount() + "/" + result.redisOnlyMembers() + "/" + result.dbOnlyMembers());
            assertThat(result.requests().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(requestCount);
            assertThat(result.requests()).doesNotContainKey(IssueOutcome.ERROR);
            // DB 기준 정합성은 모든 방식에서 지켜져야 한다 (조건부 UPDATE, UNIQUE 가 마지막에 막는다)
            assertThat(result.checks().atMostOncePerMember()).isTrue();
            assertThat(result.checks().notOverIssued()).isTrue();
            assertThat(result.checks().issuedCountMatchesRows()).isTrue();
            if (method != CouponIssueStrategy.PESSIMISTIC) {
                // 모든 요청이 끝난 뒤 Redis 예약과 DB 발급이 같은 회원 집합이어야 한다
                assertThat(result.redisOnlyMembers()).isZero();
                assertThat(result.dbOnlyMembers()).isZero();
            }
            if (method == CouponIssueStrategy.REDIS_MULTI_EXEC || method == CouponIssueStrategy.REDIS_LUA
                    || method == CouponIssueStrategy.REDIS_WATCH) {
                assertThat(result.requests()).doesNotContainKey(IssueOutcome.SOLD_OUT_AT_DB);
            }
        }
        append("\nRedis SCARD / Redis 에만 있는 회원 / DB 에만 있는 회원 (회차별): " + redisVsDb + "\n\n");
    }

    // ---------------------------------------------------------------

    private Future<String> send(Long couponId, Long memberId, CouponIssueStrategy method) {
        return executor.submit(() -> {
            try {
                labService.issue(couponId, memberId, method);
                return "SUCCESS";
            } catch (SimulatedCrash crash) {
                return "CRASHED";
            } catch (RuntimeException e) {
                return IssueOutcome.of(e).name();
            }
        });
    }

    private String state(Long couponId) {
        return "Redis " + new TreeSet<>(redisRepository.members(couponId))
                + ", DB 발급 행 " + issueCouponRepository.countByCouponId(couponId)
                + ", issued_count " + couponRepository.findById(couponId).orElseThrow().getIssuedCount();
    }

    // 모든 요청이 끝난 뒤: DB 는 재고 이하·행 수 일치, Redis 예약 회원 = DB 발급 회원
    private void assertConsistent(Long couponId, int totalCount) {
        LabResult result = labService.result(couponId);
        assertThat(result.issueRows()).isLessThanOrEqualTo(totalCount);
        assertThat(result.issuedCount()).isEqualTo((int) result.issueRows());
        assertThat(result.redisOnlyMembers()).isZero();
        assertThat(result.dbOnlyMembers()).isZero();
    }

    private Pause arm(Point point, Long memberId) {
        Pause pause = new Pause();
        failurePoints.arm(point, memberId, pause);
        return pause;
    }

    private static FailurePoints.Action dbFailure() {
        return () -> {
            throw new DataAccessResourceFailureException("주입한 DB 확정 실패");
        };
    }

    private static FailurePoints.Action crash() {
        return () -> {
            throw new SimulatedCrash();
        };
    }

    /** 프로세스 강제 종료 흉내: RuntimeException 이 아니라서 보상(catch RuntimeException)이 실행되지 않는다. */
    static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("프로세스 강제 종료 흉내", null, false, false);
        }
    }
}
