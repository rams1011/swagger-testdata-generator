package com.tx.api.test.generator.catalog;

import java.util.List;

public record OperationPage(
        String key,
        String method,
        String path,
        String rawPath,
        String summary,
        String description,
        String tag,
        List<ParameterField> parameters,
        RequestBodySchema requestBody,
        ResponseSchema response,
        List<Integer> statusCodes,
        OperationDefaults defaults,
        List<String> requestContentTypes,
        OperationAuth auth,
        List<SpecServer> servers
) {
}
