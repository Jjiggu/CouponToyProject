package couponToy.CouponToyProject.Member;

import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.Member.service.MemberService;
import couponToy.CouponToyProject.global.constant.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class MemberServiceTest {

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberService memberService;

    @Test
    void 회원가입() {

        //Given
        Member request = Member.builder()
                .email("test@test.com")
                .password("test123!@")
                .name("테스트유저")
                .role(Role.MEMBER)
                .build();

        //When
        Member signUpMember = memberRepository.save(request);
//        Member savedMember = memberService.signUpMember();

        //Then
        assertThat(signUpMember.getMemberId()).isNotNull();
        assertThat(signUpMember.getEmail()).isEqualTo("test@test.com");
        assertThat(signUpMember.getName()).isEqualTo("테스트유저");
        assertThat(signUpMember.getRole()).isEqualTo(Role.MEMBER);

    }
}
