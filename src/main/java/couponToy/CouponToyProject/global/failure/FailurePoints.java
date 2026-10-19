package couponToy.CouponToyProject.global.failure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 장애 실험용 실패 지점. 아무것도 걸어 두지 않으면 아무 일도 하지 않는다.
 * - 테스트는 회원 단위로 지점에 동작을 걸어, 그 요청을 멈추거나 실패시키거나 프로세스가 죽은 것처럼 끊는다(한 번만 동작).
 * - lab.halt-at 설정(LAB_HALT_AT)을 주면 그 지점에 처음 도달한 요청에서 JVM 을 즉시 종료한다. kill -9 와 같은 실험용이다.
 */
@Component
public class FailurePoints {

    public enum Point {
        SET_ONLY_BEFORE_SADD,       // SET 명령만 쓰는 예약: SISMEMBER, SCARD 로 확인한 뒤 SADD 전
        MULTI_EXEC_BEFORE_SREM,     // MULTI/EXEC 예약: EXEC 결과가 재고 초과라 되돌리는 SREM 전
        WATCH_BEFORE_EXEC,          // WATCH 예약: 읽고 확인한 뒤 MULTI/EXEC 전
        AFTER_RESERVE,              // Redis 예약 성공 후 DB 확정 전
        DB_BEFORE_COMMIT,           // DB 확정 트랜잭션 안, 커밋 전
        BEFORE_CANCEL,              // DB 확정 실패 후 Redis 예약을 되돌리는 SREM 전
        ASYNC_BEFORE_CONFIRM,       // 4단계: 작업 스레드가 스레드 풀 큐에서 작업을 꺼낸 뒤 DB 확정 전
        PUBSUB_BEFORE_CONFIRM       // 5단계: 구독자의 작업 스레드가 메시지 처리를 시작한 뒤 DB 확정 전
    }

    public interface Action {
        void run();
    }

    private final Map<String, Action> armed = new ConcurrentHashMap<>();
    private final Point haltAt;

    public FailurePoints(@Value("${lab.halt-at:}") String haltAt) {
        this.haltAt = haltAt.isBlank() ? null : Point.valueOf(haltAt);
    }

    public void reach(Point point, Long memberId) {
        if (point == haltAt) {
            Runtime.getRuntime().halt(137); // 종료 훅도 실행하지 않는다
        }
        Action action = armed.remove(key(point, memberId));
        if (action != null) {
            action.run();
        }
    }

    public void arm(Point point, Long memberId, Action action) {
        armed.put(key(point, memberId), action);
    }

    public void clear() {
        armed.clear();
    }

    private static String key(Point point, Long memberId) {
        return point + ":" + memberId;
    }
}
