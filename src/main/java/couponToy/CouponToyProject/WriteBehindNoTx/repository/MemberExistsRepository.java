package couponToy.CouponToyProject.WriteBehindNoTx.repository;

import couponToy.CouponToyProject.Member.model.Member;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 10단계: 회원 확인을 트랜잭션 없이 SELECT 한 번으로 한다.
 *
 * 9단계는 MemberRepository.existsById 를 썼다. existsById 는 Spring Data 가 만든 구현체(SimpleJpaRepository)의 메서드이고,
 * 이 클래스에는 @Transactional(readOnly = true) 가 붙어 있다. 그래서 회원 확인 한 번이 읽기 전용 트랜잭션 하나가 되고,
 * MySQL 드라이버(Connector/J)는 트랜잭션 시작·끝에 바꾸는 커넥션 설정(setAutoCommit, setReadOnly)을 SET 문장으로 보낸다.
 *
 *   9단계 (요청 하나에 6문장)                          10단계 (요청 하나에 1문장)
 *   SET autocommit=0                  트랜잭션 시작      SELECT count(...) FROM members WHERE member_id = ?
 *   SET SESSION TRANSACTION READ ONLY 읽기 전용 표시
 *   SELECT COUNT(*) FROM members WHERE member_id = ?
 *   COMMIT                            트랜잭션 끝
 *   SET SESSION TRANSACTION READ WRITE 원래대로
 *   SET autocommit=1                  원래대로
 *
 * EC2 에서 잰 값(9단계 문서 4절): 요청 하나에 MySQL CPU 약 340µs 였고, 그중 SELECT 는 115µs, 나머지 약 225µs 가 위의 설정 문장과 COMMIT 이다.
 * 카카오페이 글(https://tech.kakaopay.com/post/jpa-transactional-bri/ "실제로 set_option과 commit이 성능에 영향을 미칠까?")과 같은 문제이고,
 * 같은 해법을 쓴다: readOnly 에 propagation = SUPPORTS.
 *
 * Repository 만 상속하고 JpaRepository 는 상속하지 않는다. 이 인터페이스에 있는 메서드는 아래 하나뿐이고,
 * SimpleJpaRepository 의 기본 메서드(existsById 등)와 그 클래스 단위 @Transactional(readOnly = true)가 여기에는 딸려 오지 않는다.
 */
public interface MemberExistsRepository extends Repository<Member, Long> {

    /**
     * 회원이 있으면 1, 없으면 0. 9단계 existsById 가 만들던 SQL 과 같은 COUNT 모양이라, 두 단계의 차이는 트랜잭션 설정 문장뿐이다.
     *
     * propagation = SUPPORTS: 부르는 쪽에 트랜잭션이 있으면 거기에 참여하고, 없으면 트랜잭션 없이 실행한다.
     *   트랜잭션이 없으면 Spring 은 커넥션 설정을 바꾸지 않는다. 커넥션 풀(Hikari)의 커넥션은 자동 커밋(autocommit=1)이 기본이라,
     *   드라이버는 SELECT 하나만 보내고 MySQL 은 그 문장을 스스로 커밋한다. SET 문장도 COMMIT 문장도 나가지 않는다.
     * readOnly = true: 트랜잭션 없이 실행될 때는 아무 일도 하지 않는다. 바깥 트랜잭션에 참여할 때만 의미가 있고, "읽기만 한다" 는 표시로 둔다.
     *
     * 다른 전파 옵션을 쓰지 않은 이유(카카오페이 글과 같은 판단)
     *   NOT_SUPPORTED: 바깥 트랜잭션이 있으면 그것을 잠시 멈추고 따로 실행한다. 이때 커넥션을 하나 더 쓰게 될 수 있다
     *   NEVER: 바깥 트랜잭션이 있으면 예외를 던진다. 나중에 트랜잭션 안에서 부르게 되면 바로 깨진다
     *
     * 이 방법이 통하는 조건: 부르는 쪽에 트랜잭션이 없어야 한다.
     *   지금은 LabService.issue() → WriteBehindNoTxIssueService.issue() → 여기 순서로 오고, 앞의 둘에는 @Transactional 이 없다.
     *   OSIV(open-in-view)도 꺼져 있어(application.yml) 요청 전체를 감싸는 영속성 컨텍스트나 트랜잭션도 없다.
     *   누가 바깥에 @Transactional 을 붙이면 SUPPORTS 는 그 트랜잭션에 참여하고, 설정 문장은 바깥 트랜잭션 몫으로 다시 나간다.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    @Query("select count(m) from Member m where m.memberId = :memberId")
    long countByMemberId(@Param("memberId") Long memberId);
}
