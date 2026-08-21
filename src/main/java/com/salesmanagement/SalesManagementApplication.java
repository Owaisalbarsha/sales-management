package com.salesmanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SalesManagementApplication {

	public static void main(String[] args) {
		SpringApplication.run(SalesManagementApplication.class, args);
	}

}
