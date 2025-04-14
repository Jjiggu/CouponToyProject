package couponToy.CouponToyProject.queue.util;


public final class RedisKeyUtils {

    public static final String WAITING_KEY_PREFIX = "coupon:waiting:";
    public static final String ISSUED_KEY_PREFIX = "coupon:issued:";
    public static final String FAILED_KEY_PREFIX = "coupon:failed:";


    private RedisKeyUtils() {
        throw new UnsupportedOperationException("유틸리티 서비스는 인스턴스화 할 수 없습니다.");
    }

    public static String buildQueueKey(Long couponId) {
        return WAITING_KEY_PREFIX + couponId;
    }

    public static String buildIssuedKey(Long couponId) {
        return ISSUED_KEY_PREFIX + couponId;
    }

    public static String buildFailedKey(Long couponId) {
        return FAILED_KEY_PREFIX + couponId;
    }


    public static Long extractCouponIdFromKey(String prefix, String key) {
        if (!key.startsWith(prefix)) {
            throw new IllegalArgumentException("키(" + key + ")는 접두어(" + prefix + ")로 시작하지 않습니다.");
        }

        String idPart = key.substring(prefix.length());
        return Long.valueOf(idPart);
    }
}
