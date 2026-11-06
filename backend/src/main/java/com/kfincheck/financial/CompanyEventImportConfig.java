package com.kfincheck.financial;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "company-events.import-enabled", havingValue = "true")
class CompanyEventImportConfig {
    @Bean
    ApplicationRunner importCompanyEvents(CompanyEventImporter importer, ConfigurableApplicationContext context,
            @Value("${company-events.stock}") String stock, @Value("${company-events.year}") int year) {
        return args -> {
            if (!stock.matches("[0-9]{6}") || year < 2015 || year > 9999) {
                throw new IllegalArgumentException("Provide a six-digit stock code and a valid filing year.");
            }
            int count = importer.collect(stock, year);
            org.slf4j.LoggerFactory.getLogger(CompanyEventImportConfig.class)
                    .info("Company event import completed: stock={}, year={}, matched={}", stock, year, count);
            SpringApplication.exit(context);
        };
    }
}
