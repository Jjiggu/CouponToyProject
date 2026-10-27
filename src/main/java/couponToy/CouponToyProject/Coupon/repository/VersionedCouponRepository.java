package couponToy.CouponToyProject.Coupon.repository;

import couponToy.CouponToyProject.Coupon.model.VersionedCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionedCouponRepository extends JpaRepository<VersionedCoupon, Long> {
}
