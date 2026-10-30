package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import com.bilolbek.bankProductCreation.config.DocumentPipelineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({AppProperties.class,DocumentPipelineProperties.class})
public class BankProductCreationApplication {

	public static void main(String[] args) {
		SpringApplication.run(BankProductCreationApplication.class, args);
	}

}
