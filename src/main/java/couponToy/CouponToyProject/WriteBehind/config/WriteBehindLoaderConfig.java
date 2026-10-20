package couponToy.CouponToyProject.WriteBehind.config;

import couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 9단계 적재기 프로세스(SPRING_PROFILES_ACTIVE=wb-loader). 스레드 하나가 WriteBehindLoader.loadOnce() 를 계속 부른다.
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
        loader.ensureGroup();
        thread = new Thread(this::run, "wb-loader");
        thread.start();
    }

    // ponytail: 비어 있으면 20ms 쉬고 다시 읽는다. 블로킹 XREADGROUP 은 부를 때마다 연결을 새로 맺어서(6단계) 쓰지 않는다
    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (loader.loadOnce() == 0) {
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // ACK 하지 않았으므로 다음에 pending 으로 다시 읽는다
                log.warn("write-behind load failed, will retry {}", e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

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
