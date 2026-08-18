package org.synanton.equalix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EqualixApplication {

    public static void main(String[] args) {
        SpringApplication.run(EqualixApplication.class, args);
    }
}
