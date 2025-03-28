package couponToy.CouponToyProject.Coupon.repository;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

}
