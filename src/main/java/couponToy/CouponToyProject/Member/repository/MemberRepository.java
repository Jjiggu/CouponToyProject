package couponToy.CouponToyProject.Member.repository;

import couponToy.CouponToyProject.Member.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);
    Optional<Member> findByName(String nickName);
}
