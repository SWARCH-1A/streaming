package streaming.channels;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChannelsApplication {
    public static void main(String[] args) { SpringApplication.run(ChannelsApplication.class,args); }
}
