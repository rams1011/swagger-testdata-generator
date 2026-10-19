package com.tx.api.test.generator.studio.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class HostingProfileValidator {

    private static final Logger log = LoggerFactory.getLogger(HostingProfileValidator.class);

    private final Environment environment;
    private HostingProfile hostingProfile;

    public HostingProfileValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        hostingProfile = HostingProfile.requireExactlyOne(environment.getActiveProfiles());
        log.info("Hosting profile: {}", hostingProfile.wireValue());
    }

    public HostingProfile hostingProfile() {
        return hostingProfile;
    }
}
