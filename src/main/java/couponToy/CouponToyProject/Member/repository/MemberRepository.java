package couponToy.CouponToyProject.Member.repository;

import couponToy.CouponToyProject.Member.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
