package couponToy.CouponToyProject.WriteBehind.service;

import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.GROUP;
import static couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService.STREAM;

/**
 * 9단계: Redis 의 발급 기록을 DB 로 옮긴다.
 * - loadOnce(): 스트림에서 여러 건을 읽어 한 트랜잭션에 INSERT 만 하고, 커밋 뒤 ACK 한다. 쿠폰 행(issued_count)은 갱신하지 않는다
 * - reconcile(): Redis 명단과 DB 행을 맞춘다. Redis 에만 있는 회원을 넣고, issued_count 를 행 수로 한 번에 맞춘다
 * INSERT 는 이미 있는 행이면 아무것도 하지 않으므로(UNIQUE), 같은 메시지를 다시 처리하거나 대사와 겹쳐도 결과가 같다.
 *
 * Redis Stream 과 소비자 그룹 (이 클래스가 쓰는 만큼만)
 * - 스트림: 메시지를 끝에 붙이기만 하는 기록장. 메시지마다 ID(밀리초 시각-순번)와 필드-값 쌍이 있고, 읽어 가도 남아 있다
 * - 소비자 그룹: 같은 스트림을 여러 소비자가 나눠 읽게 해 주는 장치. 그룹은 두 가지를 기억한다
 *     last-delivered-id  그룹이 어디까지 나눠 줬는지. ">" 로 읽으면 이 뒤의 새 메시지를 받고, 이 값이 앞으로 간다
 *     pending(PEL)       소비자에게 나눠 줬지만 아직 XACK 를 받지 못한 메시지 목록. 소비자 이름별로 따로 쌓인다
 * - 비유하면 스트림은 주문서 꽂이, 그룹은 주방이다. 주방은 "어디까지 가져갔는지" 와 "가져갔지만 다 됐다고 말하지 않은 주문서" 를 적어 둔다.
 *   요리사(loader)가 쓰러졌다 돌아오면 그 목록부터 다시 만든다
 *
 * 이 클래스가 보내는 명령
 *   XGROUP CREATE coupon:wb:stream coupon-wb-loaders 0-0 MKSTREAM          ensureGroup()  그룹 만들기
 *   XREADGROUP GROUP coupon-wb-loaders loader COUNT 100 STREAMS 스트림 0   read("0")      내 pending 다시 받기
 *   XREADGROUP GROUP coupon-wb-loaders loader COUNT 100 STREAMS 스트림 >   read(">")      새 메시지 받기
 *   XACK coupon:wb:stream coupon-wb-loaders id...                          loadOnce()     처리 끝. pending 에서만 빠지고 메시지는 스트림에 남는다
 *   XPENDING coupon:wb:stream coupon-wb-loaders id id 1                    deliveries()   이 메시지를 몇 번 받았는지(전달 횟수). "0" 으로 다시 읽을 때도 1 늘어난다
 *   XADD coupon:wb:stream:dead * ...                                       deadLetter()   전달 횟수 상한을 넘긴 메시지를 따로 보관
 * 이 코드는 메시지를 지우거나(XDEL) 자르지(XTRIM) 않는다. 스트림은 계속 길어지고, 실험 초기화(DELETE /lab/data)가 키째 지운다.
 *
 * 적재가 실패했을 때의 흐름 (loadOnce → loadOneByOne → deadLetter)
 *   묶음 INSERT 실패
 *     └ 한 건씩 다시 INSERT
 *         ├ 성공한 메시지: 바로 XACK. 문제 메시지 때문에 같이 막히지 않는다
 *         └ 실패한 메시지: XPENDING 으로 전달 횟수 확인
 *             ├ 상한(기본 3) 이하: ACK 하지 않고 pending 에 남긴다 → 예외를 던져 적재 루프가 1초 쉰 뒤 다시 읽는다
 *             └ 상한 초과: coupon:wb:stream:dead 에 XADD 하고 XACK. 예약(Redis 명단)은 그대로 두고, 대사가 나중에 DB 에 넣는다
 *   예) DB 가 B 만 계속 거절하면: 1번째 받음(실패, 남김) → 1초 → 2번째 → 1초 → 3번째 → 1초 → 4번째 받음(상한 초과, 따로 보관)
 *       그동안 pending 을 먼저 읽으므로 새 메시지는 약 3초 기다린다. A·C 처럼 같은 묶음의 다른 메시지는 첫 번째에 이미 들어간다
 */
@Slf4j
@Component
public class WriteBehindLoader {

    // 전달 횟수 상한을 넘겨 적재를 포기한 메시지를 따로 보관하는 스트림. 실험 초기화(DELETE /lab/data)가 coupon:* 로 함께 지운다
    public static final String DEAD_LETTER = "coupon:wb:stream:dead";

    // 소비자 이름. pending 은 이름별로 쌓이므로, 적재기가 죽었다가 같은 이름으로 다시 뜨면 자기 pending 을 "0" 으로 다시 읽을 수 있다.
    // 6단계는 이름에 pid 를 넣어서(consumer-{pid}-n), 죽은 소비자의 pending 을 XAUTOCLAIM 으로 가져와야 했다
    // ponytail: 적재기 프로세스 하나. 여럿이면 이름을 나누고, 죽은 쪽 pending 을 XAUTOCLAIM 으로 가져와야 한다(6단계)
    static final String CONSUMER = "loader";

    // 대사 결과. redisMembers: Redis 명단 수, dbRowsBefore: 대사 전 DB 행 수, insertedFromRedis: 이번에 DB 에 넣은 회원,
    // dbOnlyMembers: DB 에만 있는 회원(Redis 가 잃은 기록), issuedCount: 맞춘 뒤 issued_count, totalCount: 재고
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
    // 한 번에 읽고 한 트랜잭션에 넣는 최대 건수(XREADGROUP 의 COUNT). 쌓인 것이 적으면 있는 만큼만 읽힌다
    private final int batchSize;
    // 이 횟수보다 많이 받고도 적재하지 못한 메시지는 따로 보관한다(6단계와 같은 기본값 3)
    private final long maxDeliveries;

    public WriteBehindLoader(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                             CouponIssuedRedisRepository couponIssuedRedisRepository,
                             @Value("${coupon.issue.write-behind.batch-size:100}") int batchSize,
                             @Value("${coupon.issue.write-behind.max-deliveries:3}") long maxDeliveries) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.couponIssuedRedisRepository = couponIssuedRedisRepository;
        this.batchSize = batchSize;
        this.maxDeliveries = maxDeliveries;
    }

    /**
     * 읽고 ACK 하지 못한 자기 메시지(pending)를 먼저, 없으면 새 메시지를 batchSize 만큼 읽어 적재한다. 처리한 메시지 수를 돌려준다.
     * 커밋 전에 실패하면 ACK 하지 않으므로 다음 호출에서 pending 으로 다시 읽힌다.
     * 묶음이 실패하면 한 건씩 다시 넣어 본다(loadOneByOne). 문제없는 메시지는 바로 ACK 하고, 실패한 메시지만 pending 에 남긴다.
     * 실패한 메시지는 전달 횟수가 상한을 넘으면 따로 보관(dead letter)하고 ACK 한다. 그 전까지는 pending 을 먼저 읽으므로 새 메시지가
     * 잠깐(상한 × 적재 루프의 재시도 간격, 기본 약 3초) 기다린다.
     */
    public int loadOnce() {
        // 1. 내 pending 먼저. 지난번에 읽고 ACK 하기 전에 실패했거나 죽어서 남은 메시지다.
        //    ">" 는 한 번 나눠 준 메시지를 다시 주지 않으므로, 이것을 건너뛰면 그 메시지는 영영 적재되지 않는다
        List<MapRecord<String, Object, Object>> records = read(ReadOffset.from("0"));
        // 2. pending 이 없으면(평소) 새 메시지를 받는다. lastConsumed() 가 ">" 다. 받는 순간 내 pending 에 올라간다
        if (records.isEmpty()) {
            records = read(ReadOffset.lastConsumed());
        }
        if (records.isEmpty()) {
            return 0;
        }
        // 3. 읽은 것을 한 트랜잭션, 여러 행 INSERT 한 문장으로 넣는다. 커밋(디스크 동기화)을 묶음당 한 번만 기다린다.
        //    쿠폰 행(issued_count)은 건드리지 않아, 8단계처럼 모든 확정이 쿠폰 행 하나의 락에 줄 서지 않는다. issued_count 는 대사가 맞춘다
        try {
            List<MapRecord<String, Object, Object>> batch = records;
            transactionTemplate.executeWithoutResult(status -> insert(rows(batch)));
        } catch (RuntimeException batchFailure) {
            // 묶음 전체가 롤백됐다(한 트랜잭션이므로 한 행이라도 실패하면 아무것도 안 들어간다). 아직 아무것도 ACK 하지 않았다.
            // 원인이 DB 장애(전부 실패)인지 특정 메시지(한두 건만 실패)인지 여기서는 모른다. 한 건씩 다시 넣어 가려낸다
            loadOneByOne(records, batchFailure);
            return records.size();
        }
        // 4. 커밋된 뒤에만 ACK 한다. 3에서 예외가 나면 여기까지 오지 않으므로 pending 에 남아 다음 호출의 1에서 다시 읽힌다.
        //    순서가 반대(ACK 먼저)면, ACK 와 커밋 사이에 죽었을 때 메시지는 pending 에서 빠졌는데 DB 에는 없다(대사만 살릴 수 있다).
        //    지금 순서에서 커밋과 ACK 사이에 죽으면 다시 읽어 INSERT 하지만, 이미 있는 행이라 바뀌지 않는다
        ack(records);
        return records.size();
    }

    // 묶음 중 어느 메시지가 문제인지 모르므로 한 건씩 다시 넣는다. 넣은 것은 바로 ACK 한다.
    // 실패한 것은 전달 횟수가 상한을 넘었으면 따로 보관하고, 아니면 pending 에 남긴다.
    // 남긴 것이 있으면 묶음 실패를 다시 던져 적재 루프가 잠깐 쉬게 한다. 바로 다시 읽으면 DB 가 잠깐 죽은 동안 전달 횟수만 빨리 올라간다
    private void loadOneByOne(List<MapRecord<String, Object, Object>> records, RuntimeException batchFailure) {
        // 이번 호출에서 pending 에 남긴(다시 해 볼) 메시지가 있는지
        boolean retry = false;
        for (MapRecord<String, Object, Object> record : records) {
            try {
                // 메시지 하나를 자기 트랜잭션 하나로 넣는다. 다른 메시지의 실패에 휩쓸려 롤백되지 않는다
                transactionTemplate.executeWithoutResult(status -> insert(rows(List.of(record))));
                // 커밋된 메시지만 ACK. 묶음 경로와 같은 규칙(커밋 뒤에만 ACK)이다
                ack(List.of(record));
            } catch (RuntimeException e) {
                // 이 메시지는 넣지 못했다. 지금까지 몇 번 받았는지 본다(이번 읽기까지 포함한 값)
                long deliveries = deliveries(record.getId());
                if (deliveries > maxDeliveries) {
                    // 충분히 다시 해 봤는데도 안 된다. 따로 보관하고 ACK 해서 pending 에서 뺀다. 이제 새 메시지를 막지 않는다
                    deadLetter(record, deliveries, e);
                } else {
                    // 아직 상한 전. ACK 하지 않으므로 pending 에 남고, 다음 loadOnce() 의 read("0") 에서 다시 받는다(전달 횟수 +1)
                    retry = true;
                }
            }
        }
        if (retry) {
            // 남긴 메시지가 있으면 실패로 끝낸다. 적재 루프(WriteBehindLoaderConfig.run)가 이 예외를 받고 1초 쉰 뒤 다시 부른다.
            // 예외 없이 돌아가면 루프가 쉬지 않고 바로 다시 읽어, DB 가 잠깐 죽은 동안에도 전달 횟수가 순식간에 상한을 넘는다
            throw batchFailure;
        }
        // 여기까지 오면 이번 묶음은 모두 넣었거나 따로 보관했다. pending 이 비었으므로 다음 호출은 새 메시지를 읽는다
    }

    // 6단계와 달리 예약(Redis 명단)은 되돌리지 않는다. 9단계에서는 Redis 가 기준이라 이 회원은 이미 발급(202 접수)된 것이고,
    // DB 에 옮기는 일만 실패한 것이다. 명단에 남아 있으므로 대사가 나중에 DB 에 넣는다.
    // 보관(XADD)과 ACK 사이에 죽으면 다시 보관되어 같은 메시지가 두 번 남을 수 있다
    // 보관되는 메시지 예: {couponId=4, memberId=35, originalId=1792678236620-0, deliveries=4, error=...SQL state [45000]...}
    private void deadLetter(MapRecord<String, Object, Object> record, long deliveries, RuntimeException e) {
        // 1. 원래 메시지의 필드(memberId, couponId)를 그대로 옮긴다. 대사나 사람이 나중에 어떤 발급인지 알 수 있게
        Map<String, String> dead = new TreeMap<>();
        record.getValue().forEach((k, v) -> dead.put(String.valueOf(k), String.valueOf(v)));
        // 2. 추적용 정보: 원래 스트림의 메시지 ID, 몇 번 받고 포기했는지, 마지막 실패 원인(SQL 오류는 길어서 300자까지만)
        dead.put("originalId", record.getId().getValue());
        dead.put("deliveries", String.valueOf(deliveries));
        dead.put("error", e.toString().length() > 300 ? e.toString().substring(0, 300) : e.toString());
        // 3. XADD coupon:wb:stream:dead * couponId .. memberId .. originalId .. deliveries .. error ..
        redisTemplate.opsForStream().add(StreamRecords.string(dead).withStreamKey(DEAD_LETTER));
        // 4. 원래 메시지를 ACK 해서 pending 에서 뺀다. 보관(3)보다 먼저 ACK 하면, 그 사이에 죽었을 때 어디에도 기록이 남지 않는다
        ack(List.of(record));
        log.warn("write-behind message moved to dead letter id={} memberId={} deliveries={}", record.getId(), dead.get("memberId"), deliveries);
    }

    // XPENDING 으로 본 전달 횟수. 새로 받을 때 1, pending 을 "0" 으로 다시 받을 때마다 1 씩 는다(Redis 7.2 에서 확인)
    // XPENDING coupon:wb:stream coupon-wb-loaders {id} {id} 1
    //   시작·끝 ID 를 같게 주어 이 메시지 하나만 본다. 결과에는 [ID, 소비자 이름, 마지막으로 받은 뒤 지난 시간, 전달 횟수] 가 온다
    //   이미 ACK 된 메시지라 pending 에 없으면 빈 결과다(0 을 돌려준다)
    private long deliveries(RecordId id) {
        PendingMessages pending = redisTemplate.opsForStream().pending(STREAM, GROUP, Range.closed(id.getValue(), id.getValue()), 1L);
        return pending.isEmpty() ? 0 : pending.get(0).getTotalDeliveryCount();
    }

    // 메시지 하나가 MapRecord 하나다. getId() 는 스트림 ID, getValue() 는 XADD 때 넣은 필드 {memberId, couponId}(값은 문자열)
    // 값이 숫자가 아니면 여기서 예외가 나고, 그 메시지는 넣지 못한 것으로 다룬다
    private static List<long[]> rows(List<MapRecord<String, Object, Object>> records) {
        return records.stream()
                .map(r -> new long[]{Long.parseLong((String) r.getValue().get("couponId")), Long.parseLong((String) r.getValue().get("memberId"))})
                .toList();
    }

    // XACK coupon:wb:stream coupon-wb-loaders id1 id2 ...
    //   그룹의 pending 에서 이 메시지들을 뺀다. 메시지 자체는 스트림에 남는다(XDEL 이 아니다)
    private void ack(List<MapRecord<String, Object, Object>> records) {
        redisTemplate.opsForStream().acknowledge(STREAM, GROUP, records.stream().map(MapRecord::getId).toArray(RecordId[]::new));
    }

    /**
     * Redis 명단(기준)과 DB 행을 비교해 Redis 에만 있는 회원을 넣고, issued_count 를 행 수로 맞춘다.
     * DB 에만 있는 회원은 Redis 가 기록을 잃었다는 뜻이라 되돌리지 않고 알려만 준다.
     * DB 를 먼저 읽는다. Lua 가 SADD 와 XADD 를 한 번에 하므로 DB 에 들어온 회원은 그 전에 Redis 에 있었다.
     * 그래서 요청이 계속 들어오는 중에도 DB 에만 있는 회원은 Redis 가 잃은 경우뿐이다.
     */
    public Reconciliation reconcile(Long couponId) {
        return transactionTemplate.execute(status -> {
            // 1. DB 명단 먼저. 반대로 Redis 를 먼저 읽으면, 그 뒤에 들어와 적재까지 끝난 회원이 "DB 에만 있는 회원" 으로 잘못 잡힌다
            Set<Long> db = new HashSet<>(jdbcTemplate.queryForList(
                    "SELECT member_id FROM coupon_issues WHERE coupon_id = ?", Long.class, couponId));
            // 2. Redis 명단(SMEMBERS coupon:issued:{id}). 9단계에는 SREM 이 없어서 명단은 늘기만 한다
            Set<Long> redis = couponIssuedRedisRepository.members(couponId);
            // 3. Redis 에만 있는 회원: 적재기가 아직 못 넣었거나, 적재 요청(스트림 메시지)이 사라진 경우. 기준은 Redis 이므로 DB 에 넣는다.
            //    적재기가 같은 회원을 동시에 넣어도 INSERT 가 이미 있는 행을 건드리지 않으므로 결과가 같다
            List<Long> missing = redis.stream().filter(m -> !db.contains(m)).sorted().toList();
            // 4. DB 에만 있는 회원: Redis 가 기록을 잃은 경우(영속화 끔 + 재시작). 이미 202 로 접수된 회원이라 지우지 않고 알려만 준다
            List<Long> dbOnly = db.stream().filter(m -> !redis.contains(m)).sorted().toList();
            insert(missing.stream().map(m -> new long[]{couponId, m}).toList());
            // 5. issued_count 를 실제 행 수로 덮어쓴다. 적재기는 쿠폰 행을 갱신하지 않으므로 이 값은 대사 때만 맞는다
            jdbcTemplate.update("UPDATE coupons SET issued_count = (SELECT COUNT(*) FROM coupon_issues WHERE coupon_id = ?) WHERE coupon_id = ?",
                    couponId, couponId);
            // 6. 결과 보고. issuedCount > totalCount 면 초과 발급(overIssued). DB 에는 수량 검사가 없어 막지 못하고, 여기서 드러날 뿐이다
            Long issuedCount = jdbcTemplate.queryForObject("SELECT issued_count FROM coupons WHERE coupon_id = ?", Long.class, couponId);
            Long totalCount = jdbcTemplate.queryForObject("SELECT total_count FROM coupons WHERE coupon_id = ?", Long.class, couponId);
            return new Reconciliation(redis.size(), db.size(), missing, dbOnly, issuedCount, totalCount);
        });
    }

    // 그룹이 없으면 만든다(스트림도 함께). 실험 데이터 초기화(DELETE /lab/data)가 스트림과 그룹을 지우므로 다시 만들 수 있어야 한다
    // XGROUP CREATE coupon:wb:stream coupon-wb-loaders 0-0 MKSTREAM
    //   0-0      그룹이 스트림 맨 처음부터 나눠 준다. 적재기가 뜨기 전에 쌓인 요청도 빠짐없이 읽는다("$" 로 만들면 만든 뒤에 들어온 것만)
    //   MKSTREAM 스트림 키가 없으면 빈 스트림을 만든다(Spring 의 createGroup 이 붙여 준다). 그룹은 스트림 키 안에 저장되므로 스트림이 있어야 한다
    // 그룹이 이미 있으면 Redis 가 BUSYGROUP 오류를 돌려준다. 원하던 상태이므로 넘어간다
    public void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
        } catch (RedisSystemException e) {
            if (!message(e).startsWith("BUSYGROUP")) {
                throw e;
            }
        }
    }

    // XREADGROUP GROUP coupon-wb-loaders loader COUNT {batchSize} STREAMS coupon:wb:stream {offset}
    //   offset ">" 는 새 메시지, "0" 은 내 pending 을 처음부터. BLOCK 을 주지 않아 읽을 것이 없으면 바로 빈 결과가 온다
    //   NOACK 를 주지 않았으므로 받은 메시지는 pending 에 올라가고, XACK 해야 빠진다
    // NOGROUP: 그룹(또는 스트림 키)이 없다. 실험 초기화가 coupon:* 키를 지우면 스트림과 그룹이 함께 사라지므로, 다시 만들고 한 번 더 읽는다
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
    // INSERT INTO coupon_issues (...) VALUES (?, ?, NOW(6), NOW(6)), (?, ?, ...), ... ON DUPLICATE KEY UPDATE member_id = member_id
    //   여러 행을 한 문장으로 보내 DB 왕복과 문장 처리 횟수를 줄인다
    //   UNIQUE(coupon_id, member_id) 에 걸리는 행은 member_id 를 자기 값으로 바꾸는, 실제로는 아무것도 안 하는 갱신이 된다.
    //   그래서 같은 메시지를 두 번 적재해도, 대사와 겹쳐도 오류 없이 행은 하나다.
    //   INSERT IGNORE 도 중복을 건너뛰지만, 중복이 아닌 오류까지 경고로 바꿔 넘긴다. 이 문장은 중복만 받아 주고 다른 오류는 예외가 된다
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
