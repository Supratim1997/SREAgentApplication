package com.sre.agent.sreagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SreagentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SreagentApplication.class, args);
	}

}
