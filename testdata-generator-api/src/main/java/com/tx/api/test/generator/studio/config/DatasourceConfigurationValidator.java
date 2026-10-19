package com.tx.api.test.generator.studio.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DatasourceConfigurationValidator {

    private final Environment environment;

    public DatasourceConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        String url = environment.getProperty("spring.datasource.url");
        if (StringUtils.isBlank(url)) {
            throw new IllegalStateException(
                    "Studio requires SPRING_DATASOURCE_URL (spring.datasource.url). "
                            + "Use hosting profile local for embedded H2, or docker/datacenter/cloud with JDBC env vars.");
        }
    }
}
