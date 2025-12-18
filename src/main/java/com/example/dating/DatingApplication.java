package com.example.dating;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DatingApplication {

	public static void main(String[] args) {
		SpringApplication.run(DatingApplication.class, args);
	}

}
