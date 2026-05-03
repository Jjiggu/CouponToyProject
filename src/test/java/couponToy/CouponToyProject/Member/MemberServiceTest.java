package couponToy.CouponToyProject.Member;

import couponToy.CouponToyProject.Member.dto.MemberSignUpRequest;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.Member.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
public class MemberServiceTest {

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberService memberService;

    @Test
    void 회원가입() {

        //Given
        MemberSignUpRequest request = MemberSignUpRequest.builder()
                .email("test@test.com")
                .password("test123!@")
                .name("테스트유저")
                .build();

        //When
        Long memberId = memberService.signUpMember(request).getMemberId();
        Member signUpMember = memberRepository.findById(memberId).orElseThrow();

        //Then
        assertThat(signUpMember.getMemberId()).isNotNull();
        assertThat(signUpMember.getEmail()).isEqualTo("test@test.com");
        assertThat(signUpMember.getName()).isEqualTo("테스트유저");
        assertThat(signUpMember.getRole().name()).isEqualTo("MEMBER");
        assertThat(signUpMember.getPassword()).isNotEqualTo("test123!@");

    }
}
