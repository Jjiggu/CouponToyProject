package couponToy.CouponToyProject.Member.service;

import couponToy.CouponToyProject.Member.dto.MemberLogInRequest;
import couponToy.CouponToyProject.Member.dto.MemberLogInResponse;
import couponToy.CouponToyProject.Member.dto.MemberSignUpRequest;
import couponToy.CouponToyProject.Member.dto.MemberSignUpResponse;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.DuplicateMemberException;
import couponToy.CouponToyProject.global.security.JwtService;
import couponToy.CouponToyProject.global.security.MemberDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationProvider authenticationProvider;
    private final JwtService jwtService;


    @Transactional
    public MemberSignUpResponse signUpMember(MemberSignUpRequest memberSignUpRequest) {
        validateEmailDuplicated(memberSignUpRequest);

        Member member = memberSignUpRequest.toEntity();
        member.encodePassword(passwordEncoder);

        return MemberSignUpResponse.fromEntity(memberRepository.save(member));
    }

    @Transactional
    public MemberLogInResponse login(MemberLogInRequest memberLogInRequest) {
        Authentication authentication = authenticationProvider.authenticate(
                new UsernamePasswordAuthenticationToken(memberLogInRequest.getEmail(), memberLogInRequest.getPassword())
        );

        MemberDetails memberDetails = (MemberDetails) authentication.getPrincipal();
        Member member = memberRepository.getReferenceById(memberDetails.getMemberId());

        String accessToken = jwtService.issue(memberDetails.getMemberId(), memberLogInRequest.getEmail(), member.getRole());

        return MemberLogInResponse.fromEntity(member, accessToken);
    }


    private void validateEmailDuplicated(MemberSignUpRequest memberSignUpRequest) {
        memberRepository.findByEmail(memberSignUpRequest.getEmail()).ifPresent(member -> {
            throw new DuplicateMemberException(ErrorCode.DUPLICATE_EMAIL);
            }
        );
    }
}
