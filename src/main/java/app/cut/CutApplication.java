package app.cut;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CutApplication {
    public static void main(String[] args) {
        SpringApplication.run(CutApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty(name = "cut.demo", havingValue = "true")
    ApplicationRunner demoWorkshop(Workshop workshop) {
        return args -> workshop.seedDemoIfEmpty();
    }
}
