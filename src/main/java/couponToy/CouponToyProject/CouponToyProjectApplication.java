package couponToy.CouponToyProject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude={DataSourceAutoConfiguration.class})
public class CouponToyProjectApplication {

	public static void main(String[] args) {
		SpringApplication.run(CouponToyProjectApplication.class, args);
	}

}
