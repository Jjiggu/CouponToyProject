package couponToy.CouponToyProject.WriteBehindNoTx.service;

import couponToy.CouponToyProject.CouponIssue.service.RedisIssueCouponService;
import couponToy.CouponToyProject.WriteBehind.service.WriteBehindIssueService;
import couponToy.CouponToyProject.WriteBehindNoTx.repository.MemberExistsRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 10단계 요청 쪽. 9단계 write-behind(WriteBehindIssueService)와 흐름이 같고, 회원 확인 하나만 다르다.
 *
 *   [요청] 이 클래스 (local 프로필, API 프로세스)
 *          1. 회원 확인: MemberExistsRepository.countByMemberId (트랜잭션 없음, SELECT 한 문장)   ← 9단계와 다른 곳
 *          2. Lua: 중복? → 재고? → XADD coupon:wb:stream → SADD coupon:issued:{id} → 202 (QUEUED)  ← 9단계와 같음
 *   [적재] WriteBehindLoader.loadOnce() (wb-loader 프로필)       ← 9단계 것을 그대로. 같은 스트림이라 같은 적재기가 처리한다
 *   [대사] WriteBehindLoader.reconcile() (POST /lab/write-behind/coupons/{id}/reconcile)  ← 9단계 것을 그대로
 *
 * 왜 회원 확인만 바꾸나: 9단계를 EC2 에서 재 보니 한계(약 1900 req/s)에서 MySQL CPU 가 찼고, 쿼리별로 보면 CPU 를 가장 많이 쓰는 것이
 * 요청마다 하는 회원 확인이었다. 그중 대부분은 SELECT 가 아니라 트랜잭션을 열고 닫는 설정 문장이었다(MemberExistsRepository 설명).
 * 나머지를 그대로 두어야 9단계와 비교했을 때 달라진 수치를 이 문장들 몫으로 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
public class WriteBehindNoTxIssueService {

    private final MemberExistsRepository memberExistsRepository;
    private final RedisIssueCouponService redisIssueCouponService;

    // 이 메서드에는 @Transactional 을 붙이지 않는다. 붙이면 회원 확인이 그 트랜잭션에 참여해(SUPPORTS) 설정 문장이 다시 나간다
    public void issue(Long memberId, Long couponId) {
        // 1. 회원 확인. 적재기는 확인 없이 INSERT 만 하므로 없는 회원은 여기서 막는다(coupon_issues 에는 회원 외래 키가 없다).
        //    MySQL 로 가는 문장은 SELECT count(...) FROM members WHERE member_id = ? 하나다. 자동 커밋 커넥션이라 MySQL 이 스스로 커밋한다
        if (memberExistsRepository.countByMemberId(memberId) == 0) {
            throw new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER);
        }
        // 2. 9단계와 같은 Lua 한 번: 중복 → 재고 → XADD → SADD. 중복이면 IsAlreadyIssued, 품절이면 CouponSoldOutException(둘 다 409).
        //    스트림 키도 9단계와 같아서(coupon:wb:stream), wb-loader 프로세스의 같은 적재기가 DB 에 넣는다
        redisIssueCouponService.reserveAndEnqueue(memberId, couponId, WriteBehindIssueService.STREAM);
    }
}
