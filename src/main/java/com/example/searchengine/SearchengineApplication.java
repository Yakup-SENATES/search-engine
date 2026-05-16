package com.example.searchengine;

import com.example.searchengine.infrastructure.config.ConfigValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SearchengineApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(SearchengineApplication.class);
		// Register ConfigValidator before any beans are instantiated so that
		// missing required configuration aborts startup before HTTP, DB,
		// provider, or scheduler components are initialized (REQ 18.3, 18.4).
		application.addListeners(new ConfigValidator());
		application.run(args);
	}

}
