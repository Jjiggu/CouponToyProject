package couponToy.CouponToyProject.signUp.service;

import couponToy.CouponToyProject.Member.dto.MemberSignUpRequest;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.Member.service.MemberService;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.Mockito.when;

public class MemberSignUpServiceTest {
    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberService memberSignUpService;

    public MemberSignUpServiceTest() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void SignUpMember_Success() {
        //Given
        MemberSignUpRequest request = MemberSignUpRequest.builder()
                .email("test@test.com")
                .password("test123!@")
                .name("테스트유저")
                .build();
        //When
        when(memberRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());
        when(PasswordEncoder.encode(request.getPassword())).thenReturn("encodedPassword");
        when(memberRepository.save(any(Member.class))).thenReturn(new Member());

        Member signUpMember = memberSignUpService.signUpMember(request);

        //Then
        assertNotNull(signUpMember);
        verify(memberRepository, times(1)).findByEmail(request.getEmail());
        verify(passwordEncoder, times(1)).encode(request.getPassword());
        verify(memberRepository, times(1)).save(any(Member.class));
    }
}
