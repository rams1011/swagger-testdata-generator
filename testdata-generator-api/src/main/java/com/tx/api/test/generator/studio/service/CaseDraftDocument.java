package com.tx.api.test.generator.studio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;

import java.util.List;

/** Reads and writes the save request a case's unpublished changes are kept as. */
public final class CaseDraftDocument {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CaseDraftDocument() {
    }

    static String write(SaveTestDataRequest request) {
        try {
            return JSON.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("These draft changes could not be saved", exception);
        }
    }

    static SaveTestDataRequest read(String documentJson) {
        try {
            return JSON.readValue(documentJson, SaveTestDataRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("These draft changes could not be read", exception);
        }
    }

    /** Files the unpublished changes refer to. */
    public static List<String> fileIds(String documentJson) {
        return RequestFileSupport.fileIds(read(documentJson).requestBody());
    }

    /** Files a stored request body refers to. */
    public static List<String> bodyFileIds(Object requestBody) {
        return RequestFileSupport.fileIds(requestBody);
    }
}
