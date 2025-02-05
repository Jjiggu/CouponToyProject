package couponToy.CouponToyProject.Member.controller;

import couponToy.CouponToyProject.Member.dto.MemberLogInRequest;
import couponToy.CouponToyProject.Member.dto.MemberSignUpRequest;
import couponToy.CouponToyProject.Member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@RequestBody @Valid MemberSignUpRequest memberSignUpRequest) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        memberService.signUpMember(memberSignUpRequest)
                );
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid MemberLogInRequest memberLogInRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        memberService.login(memberLogInRequest)
                );
    }


}
