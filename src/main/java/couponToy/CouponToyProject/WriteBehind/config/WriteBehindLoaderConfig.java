package couponToy.CouponToyProject.WriteBehind.config;

import couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 9단계 적재기 프로세스(SPRING_PROFILES_ACTIVE=wb-loader). 스레드 하나가 WriteBehindLoader.loadOnce() 를 계속 부른다.
 * API 프로세스(local 프로필)와 따로 띄운다. 이 프로필이 아니면 이 빈이 없으므로 적재 스레드도 돌지 않는다.
 * 그래서 요청 쪽과 적재기를 따로 죽이고 살리는 실험을 할 수 있다(문서 4절 "판매 중 장애").
 * SmartLifecycle: 스프링이 빈을 다 만든 뒤 start() 를, 종료할 때 stop() 을 부른다. 직접 만든 스레드를 애플리케이션과 같이 켜고 끄려고 쓴다.
 */
@Slf4j
@Configuration
@Profile("wb-loader")
@RequiredArgsConstructor
public class WriteBehindLoaderConfig implements SmartLifecycle {

    private final WriteBehindLoader loader;
    private volatile Thread thread;

    @Override
    public void start() {
        // 첫 읽기 전에 그룹을 만들어 둔다. 요청이 먼저 쌓였어도 그룹은 스트림 처음(0-0)부터 나눠 주므로 빠지지 않는다
        loader.ensureGroup();
        thread = new Thread(this::run, "wb-loader");
        thread.start();
    }

    // ponytail: 비어 있으면 20ms 쉬고 다시 읽는다. 블로킹 XREADGROUP 은 부를 때마다 연결을 새로 맺어서(6단계) 쓰지 않는다
    // 그래서 한가할 때 들어온 요청은 DB 에 들어가기까지 최대 20ms 쯤 더 늦을 수 있다. 쌓여 있으면 쉬지 않고 바로 다음 묶음을 읽는다
    private void run() {
        // stop() 이 interrupt 하면 끝난다. 묶음 처리 도중에 끊겨도 ACK 전이면 다음 실행 때 pending 으로 다시 읽는다
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (loader.loadOnce() == 0) {
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // ACK 하지 않았으므로 다음에 pending 으로 다시 읽는다
                // DB 나 Redis 가 잠깐 죽은 경우다. 바로 다시 하면 실패와 로그가 쏟아지므로 1초 쉬고 다시 한다
                log.warn("write-behind load failed, will retry {}", e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // 종료할 때 스레드에 멈추라고 알리고(interrupt) 최대 5초 기다린다
    @Override
    public void stop() {
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }
}
