package couponToy.CouponToyProject.WriteBehind.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.GROUP;
import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.STREAM;

/**
 * 9단계: Redis 의 발급 기록을 DB 로 옮긴다.
 * - loadOnce(): 스트림에서 여러 건을 읽어 한 트랜잭션에 INSERT 만 하고, 커밋 뒤 ACK 한다. 쿠폰 행(issued_count)은 갱신하지 않는다
 * - reconcile(): Redis 명단과 DB 행을 맞춘다. Redis 에만 있는 회원을 넣고, issued_count 를 행 수로 한 번에 맞춘다
 * INSERT 는 이미 있는 행이면 아무것도 하지 않으므로(UNIQUE), 같은 메시지를 다시 처리하거나 대사와 겹쳐도 결과가 같다.
 */
@Component
public class WriteBehindLoader {

    // ponytail: 적재기 프로세스 하나. 여럿이면 이름을 나누고, 죽은 쪽 pending 을 XAUTOCLAIM 으로 가져와야 한다(6단계)
    static final String CONSUMER = "loader";

    public record Reconciliation(long redisMembers, long dbRowsBefore, List<Long> insertedFromRedis,
                                 List<Long> dbOnlyMembers, long issuedCount, long totalCount) {
        public boolean overIssued() {
            return issuedCount > totalCount;
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final int batchSize;

    public WriteBehindLoader(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                             CouponIssuedRedisRepository couponIssuedRedisRepository,
                             @Value("${coupon.issue.write-behind.batch-size:100}") int batchSize) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.couponIssuedRedisRepository = couponIssuedRedisRepository;
        this.batchSize = batchSize;
    }

    /**
     * 읽고 ACK 하지 못한 자기 메시지(pending)를 먼저, 없으면 새 메시지를 batchSize 만큼 읽어 적재한다. 처리한 메시지 수를 돌려준다.
     * 커밋 전에 실패하면 ACK 하지 않으므로 다음 호출에서 pending 으로 다시 읽힌다.
     */
    public int loadOnce() {
        List<MapRecord<String, Object, Object>> records = read(ReadOffset.from("0"));
        if (records.isEmpty()) {
            records = read(ReadOffset.lastConsumed());
        }
        if (records.isEmpty()) {
            return 0;
        }
        List<long[]> rows = records.stream()
                .map(r -> new long[]{Long.parseLong((String) r.getValue().get("couponId")), Long.parseLong((String) r.getValue().get("memberId"))})
                .toList();
        transactionTemplate.executeWithoutResult(status -> insert(rows));
        redisTemplate.opsForStream().acknowledge(STREAM, GROUP, records.stream().map(MapRecord::getId).toArray(RecordId[]::new));
        return records.size();
    }

    /**
     * Redis 명단(기준)과 DB 행을 비교해 Redis 에만 있는 회원을 넣고, issued_count 를 행 수로 맞춘다.
     * DB 에만 있는 회원은 Redis 가 기록을 잃었다는 뜻이라 되돌리지 않고 알려만 준다.
     * DB 를 먼저 읽는다. Lua 가 SADD 와 XADD 를 한 번에 하므로 DB 에 들어온 회원은 그 전에 Redis 에 있었다.
     * 그래서 요청이 계속 들어오는 중에도 DB 에만 있는 회원은 Redis 가 잃은 경우뿐이다.
     */
    public Reconciliation reconcile(Long couponId) {
        return transactionTemplate.execute(status -> {
            Set<Long> db = new HashSet<>(jdbcTemplate.queryForList(
                    "SELECT member_id FROM coupon_issues WHERE coupon_id = ?", Long.class, couponId));
            Set<Long> redis = couponIssuedRedisRepository.members(couponId);
            List<Long> missing = redis.stream().filter(m -> !db.contains(m)).sorted().toList();
            List<Long> dbOnly = db.stream().filter(m -> !redis.contains(m)).sorted().toList();
            insert(missing.stream().map(m -> new long[]{couponId, m}).toList());
            jdbcTemplate.update("UPDATE coupons SET issued_count = (SELECT COUNT(*) FROM coupon_issues WHERE coupon_id = ?) WHERE coupon_id = ?",
                    couponId, couponId);
            Long issuedCount = jdbcTemplate.queryForObject("SELECT issued_count FROM coupons WHERE coupon_id = ?", Long.class, couponId);
            Long totalCount = jdbcTemplate.queryForObject("SELECT total_count FROM coupons WHERE coupon_id = ?", Long.class, couponId);
            return new Reconciliation(redis.size(), db.size(), missing, dbOnly, issuedCount, totalCount);
        });
    }

    // 그룹이 없으면 만든다(스트림도 함께). 실험 데이터 초기화(DELETE /lab/data)가 스트림과 그룹을 지우므로 다시 만들 수 있어야 한다
    public void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
        } catch (RedisSystemException e) {
            if (!message(e).startsWith("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(batchSize), StreamOffset.create(STREAM, offset));
            return records == null ? List.of() : records;
        } catch (RedisSystemException e) {
            if (!message(e).startsWith("NOGROUP")) {
                throw e;
            }
            ensureGroup();
            return read(offset);
        }
    }

    // 이미 있는 (쿠폰, 회원) 행이면 아무것도 바꾸지 않는다
    private void insert(Collection<long[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>();
        rows.forEach(r -> {
            args.add(r[0]);
            args.add(r[1]);
        });
        jdbcTemplate.update("INSERT INTO coupon_issues (coupon_id, member_id, created_at, updated_at) VALUES "
                + String.join(", ", Collections.nCopies(rows.size(), "(?, ?, NOW(6), NOW(6))"))
                + " ON DUPLICATE KEY UPDATE member_id = member_id", args.toArray());
    }

    private static String message(RedisSystemException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message == null ? "" : message;
    }
}
