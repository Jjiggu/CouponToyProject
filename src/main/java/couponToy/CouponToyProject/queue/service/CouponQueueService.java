package couponToy.CouponToyProject.queue.service;

import couponToy.CouponToyProject.queue.repository.CouponQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponQueueService {

    private final CouponQueueRepository couponQueueRepository;

    public void registerUser(Long couponId, Long userId) {
        if (!couponQueueRepository.isAlreadyIssued(couponId, userId)) {
            couponQueueRepository.addToWaitingQueue(couponId, userId);
        }
    }

    public Long getUserRank(Long couponId, Long userId) {
        return couponQueueRepository.getUserRank(couponId, userId);
    }

    public Long getQueueSize(Long couponId) {
        return couponQueueRepository.getQueueSize(couponId);
    }

    public boolean isAlreadyIssued(Long couponId, Long userId) {
        return couponQueueRepository.isAlreadyIssued(couponId, userId);
    }
}
