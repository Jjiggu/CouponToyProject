package couponToy.CouponToyProject.Lab.service;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateResponse;
import couponToy.CouponToyProject.Coupon.service.CouponService;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.service.AsyncIssueCouponService;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.service.CouponIssueStrategy;
import couponToy.CouponToyProject.CouponIssue.service.IsolationIssueCouponService;
import couponToy.CouponToyProject.CouponIssue.service.OptimisticIssueCouponService;
import couponToy.CouponToyProject.CouponIssue.service.PessimisticIssueCouponService;
import couponToy.CouponToyProject.CouponIssue.service.RedisIssueCouponService;
import couponToy.CouponToyProject.CouponIssue.service.RedisIssueCouponService.ReservationMethod;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * 로컬 학습용 실험 진입점. 데이터 준비, 방식 선택 발급, 결과 조회, 초기화를 맡는다.
 * 모든 발급 요청은 issue() 를 지나며 여기서 도착 순서를 기록한다.
 */
@Profile("local")
@Service
@RequiredArgsConstructor
public class LabService {

    private static final String LOCK_METRICS_SQL = """
            SELECT name, count FROM information_schema.innodb_metrics
            WHERE name IN ('lock_row_lock_waits', 'lock_row_lock_time', 'lock_deadlocks', 'lock_timeouts')
            """;

    private final CouponService couponService;
    private final IsolationIssueCouponService isolationIssueCouponService;
    private final PessimisticIssueCouponService pessimisticIssueCouponService;
    private final OptimisticIssueCouponService optimisticIssueCouponService;
    private final RedisIssueCouponService redisIssueCouponService;
    private final AsyncIssueCouponService asyncIssueCouponService;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    private final Map<Long, IssueRequestLog> logs = new ConcurrentHashMap<>();

    public record MemberRange(long firstMemberId, long lastMemberId, int count) {
    }

    public record Checks(
            boolean atMostOncePerMember,
            boolean notOverIssued,
            boolean issuedCountMatchesRows,
            Boolean fullyIssued // 유효 요청이 재고 이상일 때만 판단, 아니면 null
    ) {
    }

    public record LabResult(
            long couponId,
            int totalCount,
            int issuedCount,                      // coupons.issued_count
            long issueRows,                       // coupon_issues 행 수
            long distinctMembers,
            long redisIssuedCount,                // SCARD coupon:issued:{id}. Redis 방식에서만 의미가 있다.
            long redisOnlyMembers,                // Redis 에 예약은 있는데 DB 발급 행이 없는 회원 수
            long dbOnlyMembers,                   // DB 에 발급 행은 있는데 Redis 예약이 없는 회원 수
            Checks checks,
            Map<IssueOutcome, Long> requests,     // 진입점에서 관찰한 요청 결과
            IssueRequestLog.Ordering ordering,
            IssueRequestLog.Latency latencyMs,    // 진입 → 반환. 동기 방식은 DB 커밋 포함, REDIS_ASYNC 는 접수까지
            IssueRequestLog.Latency successLatencyMs,
            Map<IssueOutcome, Long> asyncResults, // REDIS_ASYNC: 접수(ACCEPTED)된 요청의 DB 확정 결과
            long pending,                         // REDIS_ASYNC: 접수됐지만 확정 결과가 아직 없는 요청
            IssueRequestLog.Latency confirmLatencyMs, // REDIS_ASYNC: 진입 → DB 커밋 (SUCCESS 만)
            IssueRequestLog.Attempts attempts,      // 요청당 트랜잭션 시도 수 (낙관적 락 재시도)
            Map<String, Long> innodbLockMetricsDelta // 쿠폰 생성 이후 MySQL 전역 카운터 변화량
    ) {
    }

    public CouponCreateResponse createCoupon(CouponCreateRequest request) {
        CouponCreateResponse response = couponService.createCoupon(request);
        logs.put(response.getCouponId(), new IssueRequestLog(readLockMetrics()));
        return response;
    }

    @Transactional
    public MemberRange createMembers(int count) {
        String batch = "lab-" + System.nanoTime() + "-";
        List<Object[]> rows = IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Object[]{batch + i + "@lab.local", "lab" + i})
                .toList();
        // password, role 은 인증용 컬럼이라 실험에서는 의미 없는 값으로 채운다.
        jdbcTemplate.batchUpdate("INSERT INTO members (email, password, name, role) VALUES (?, '-', ?, 'MEMBER')", rows);

        Map<String, Object> range = jdbcTemplate.queryForMap(
                "SELECT MIN(member_id) AS first_id, MAX(member_id) AS last_id FROM members WHERE email LIKE ?", batch + "%");
        long first = ((Number) range.get("first_id")).longValue();
        long last = ((Number) range.get("last_id")).longValue();
        if (last - first + 1 != count) {
            throw new IllegalStateException("member_id 가 연속되지 않아 범위로 돌려줄 수 없습니다. 준비 중 다른 INSERT 가 있었는지 확인하세요.");
        }
        return new MemberRange(first, last, count);
    }

    public IssueCouponResponse issue(Long couponId, Long memberId, CouponIssueStrategy method) {
        return issue(couponId, memberId, method, true);
    }

    // duplicateCheck 는 격리 수준 방식에만 전달된다. 입력 검증은 LabController.IssueRequest 가 한다.
    public IssueCouponResponse issue(Long couponId, Long memberId, CouponIssueStrategy method, boolean duplicateCheck) {
        IssueRequestLog log = logs.computeIfAbsent(couponId, id -> new IssueRequestLog(readLockMetrics()));
        long arrival = log.arrive();
        long startedAt = System.nanoTime();
        int[] attempts = {1};
        CompletableFuture<IssueCouponResponse> confirmation = null; // REDIS_ASYNC: DB 확정이 끝나면 완료된다
        try {
            IssueCouponResponse response = switch (method) {
                case READ_COMMITTED -> isolationIssueCouponService.issueWithReadCommitted(memberId, couponId, duplicateCheck);
                case REPEATABLE_READ -> isolationIssueCouponService.issueWithRepeatableRead(memberId, couponId, duplicateCheck);
                case SERIALIZABLE -> isolationIssueCouponService.issueWithSerializable(memberId, couponId, duplicateCheck);
                case PESSIMISTIC -> pessimisticIssueCouponService.issueCoupon(memberId, couponId);
                case OPTIMISTIC -> optimisticIssueCouponService.issue(memberId, couponId);
                case OPTIMISTIC_RETRY -> optimisticIssueCouponService.issueWithRetry(memberId, couponId, n -> attempts[0] = n);
                case REDIS_SET_ONLY -> redisIssueCouponService.issue(memberId, couponId, ReservationMethod.SET_ONLY, n -> attempts[0] = n);
                case REDIS_MULTI_EXEC -> redisIssueCouponService.issue(memberId, couponId, ReservationMethod.MULTI_EXEC, n -> attempts[0] = n);
                case REDIS_WATCH -> redisIssueCouponService.issue(memberId, couponId, ReservationMethod.WATCH, n -> attempts[0] = n);
                case REDIS_LUA -> redisIssueCouponService.issue(memberId, couponId, ReservationMethod.LUA, n -> attempts[0] = n);
                case REDIS_ASYNC -> {
                    confirmation = asyncIssueCouponService.issue(memberId, couponId);
                    yield IssueCouponResponse.builder().memberId(memberId).couponId(couponId).build();
                }
            };
            IssueOutcome outcome = confirmation != null ? IssueOutcome.ACCEPTED : IssueOutcome.SUCCESS;
            log.complete(arrival, memberId, outcome, System.nanoTime() - startedAt, attempts[0]);
            if (confirmation != null) {
                // DB 확정 결과는 풀 스레드에서 따로 기록한다. 202 응답은 이것을 기다리지 않는다.
                confirmation.whenComplete((r, e) -> log.finishAsync(arrival, memberId,
                        e == null ? IssueOutcome.SUCCESS : IssueOutcome.of(e), System.nanoTime() - startedAt));
            }
            return response;
        } catch (RuntimeException e) {
            log.complete(arrival, memberId, IssueOutcome.of(e), System.nanoTime() - startedAt, attempts[0]);
            throw e;
        }
    }

    // 요청 기록을 DB 보다 먼저 읽는다. 그래야 확정 결과에 SUCCESS 로 센 요청은 아래 DB 조회에 반드시 보인다(pending 0 이면 모두).
    // DB 조회 둘은 읽기 트랜잭션 하나(REPEATABLE READ 스냅숏)에서 읽어, 그 사이 커밋으로 issued_count 와 행 수가 어긋나 보이지 않게 한다.
    @Transactional(readOnly = true)
    public LabResult result(Long couponId) {
        IssueRequestLog log = logs.getOrDefault(couponId, new IssueRequestLog(Map.of()));
        Map<IssueOutcome, Long> asyncResults = log.asyncOutcomes();
        long pending = log.pending();

        Map<String, Object> coupon = jdbcTemplate.queryForList(
                        "SELECT total_count, issued_count FROM coupons WHERE coupon_id = ?", couponId)
                .stream().findFirst()
                .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));
        int totalCount = ((Number) coupon.get("total_count")).intValue();
        int issuedCount = ((Number) coupon.get("issued_count")).intValue();

        List<Long> winners = jdbcTemplate.queryForList(
                "SELECT member_id FROM coupon_issues WHERE coupon_id = ?", Long.class, couponId);
        long distinctMembers = winners.stream().distinct().count();

        Set<Long> redisMembers = couponIssuedRedisRepository.members(couponId);

        IssueRequestLog.Ordering ordering = log.ordering(winners, totalCount);

        Checks checks = new Checks(
                winners.size() == distinctMembers,
                winners.size() <= totalCount,
                issuedCount == winners.size(),
                ordering.validRequests() >= totalCount ? winners.size() == totalCount : null
        );

        return new LabResult(
                couponId, totalCount, issuedCount, winners.size(), distinctMembers,
                couponIssuedRedisRepository.issuedCount(couponId),
                redisMembers.stream().filter(m -> !winners.contains(m)).count(),
                winners.stream().filter(m -> !redisMembers.contains(m)).distinct().count(),
                checks,
                log.countByOutcome(),
                ordering,
                log.latencyMs(false),
                log.latencyMs(true),
                asyncResults,
                pending,
                log.confirmLatencyMs(),
                log.attempts(),
                delta(log.lockMetricsAtStart(), readLockMetrics())
        );
    }

    // 로컬 실험 DB 전체를 비운다. TRUNCATE 는 AUTO_INCREMENT 도 1로 되돌린다.
    public void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE coupon_issues");
        jdbcTemplate.execute("TRUNCATE TABLE coupons");
        jdbcTemplate.execute("TRUNCATE TABLE members");
        Set<String> keys = redisTemplate.keys("coupon:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        logs.clear();
    }

    private Map<String, Long> readLockMetrics() {
        Map<String, Long> metrics = new LinkedHashMap<>();
        jdbcTemplate.query(LOCK_METRICS_SQL, rs -> {
            metrics.put(rs.getString("name"), rs.getLong("count"));
        });
        return metrics;
    }

    private static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> delta = new LinkedHashMap<>();
        after.forEach((name, value) -> delta.put(name, value - before.getOrDefault(name, value)));
        return delta;
    }
}
