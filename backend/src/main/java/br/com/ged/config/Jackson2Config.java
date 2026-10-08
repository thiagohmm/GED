package br.com.ged.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the Jackson 2 mapper used by Elasticsearch's Java client and persisted document diffs. */
@Configuration(proxyBeanMethods = false)
public class Jackson2Config {

    @Bean
    ObjectMapper legacyObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
