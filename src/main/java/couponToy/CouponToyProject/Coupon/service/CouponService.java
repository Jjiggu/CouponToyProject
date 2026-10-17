package couponToy.CouponToyProject.Coupon.service;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateResponse;
import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;

    @Transactional
    public CouponCreateResponse createCoupon(CouponCreateRequest couponCreateRequest) {

        Coupon coupon = couponRepository.save(couponCreateRequest.toEntity());
        couponIssuedRedisRepository.setTotalCount(coupon.getCouponId(), coupon.getTotalCount());

        return CouponCreateResponse.fromEntity(coupon);
    }

}
