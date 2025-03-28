package couponToy.CouponToyProject.Coupon.service;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateResponse;
import couponToy.CouponToyProject.Coupon.dto.CouponReadResponse;
import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    @Transactional
    public CouponCreateResponse createCoupon(CouponCreateRequest couponCreateRequest) {

        Coupon coupon = couponRepository.save(couponCreateRequest.toEntity());

        return CouponCreateResponse.fromEntity(coupon);
    }

    @Transactional
    public CouponReadResponse findCouponById(Long couponId) {

        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(IllegalAccessError::new);

        return CouponReadResponse.fromEntity(coupon);

    }

}
