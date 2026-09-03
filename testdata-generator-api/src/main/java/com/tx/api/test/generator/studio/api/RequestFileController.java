package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.RequestFileView;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/testdata/{caseId}/files")
public class RequestFileController {

    private final TestDataStudioService testDataStudioService;

    public RequestFileController(TestDataStudioService testDataStudioService) {
        this.testDataStudioService = testDataStudioService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RequestFileView upload(@PathVariable String caseId, @RequestPart("file") MultipartFile file)
            throws IOException {
        return testDataStudioService.saveRequestFile(caseId, file);
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable String caseId, @PathVariable String fileId) {
        StudioRequestFile file = testDataStudioService.requireRequestFile(caseId, fileId);
        String fileName = StringUtils.defaultIfBlank(file.getFileName(), "upload.bin").replace("\"", "'");
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (StringUtils.isNotBlank(file.getContentType())) {
            try {
                mediaType = MediaType.parseMediaType(file.getContentType());
            } catch (RuntimeException ignored) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(file.getContent() == null ? new byte[0] : file.getContent());
    }
}
