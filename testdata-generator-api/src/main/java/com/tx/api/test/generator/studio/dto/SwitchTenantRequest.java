package com.tx.api.test.generator.studio.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record SwitchTenantRequest(@JsonAlias("workspaceId") String tenantId) {
}
