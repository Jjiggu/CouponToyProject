package couponToy.CouponToyProject.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class testController {
    @GetMapping("/test")
    public String test() {
        return "연결 테스트입니다.";
    }
}
