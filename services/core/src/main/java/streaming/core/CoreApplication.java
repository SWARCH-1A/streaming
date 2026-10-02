package streaming.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class CoreApplication {
    public static void main(String[] args) { SpringApplication.run(CoreApplication.class,args); }
}
