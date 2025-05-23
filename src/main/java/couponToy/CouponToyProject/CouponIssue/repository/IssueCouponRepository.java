package couponToy.CouponToyProject.CouponIssue.repository;

import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IssueCouponRepository extends JpaRepository<IssueCoupon, Long> {
    long countByCouponId(Long couponId);
}
