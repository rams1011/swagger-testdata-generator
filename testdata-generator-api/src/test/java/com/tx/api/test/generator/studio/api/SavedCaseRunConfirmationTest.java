package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.RunTestRequest;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Send to Production runs the stored case, so the saved-case endpoint has to pass the typed environment name on. */
class SavedCaseRunConfirmationTest {

    @Test
    void theTypedEnvironmentNameReachesTheRun() {
        TestDataStudioService service = mock(TestDataStudioService.class);
        TestDataController controller = new TestDataController(service);
        controller.runSaved("case-1", new RunTestRequest(null, null, null, "env-1", null, 2, "Production"));
        ArgumentCaptor<RunTestRequest> sent = ArgumentCaptor.forClass(RunTestRequest.class);
        verify(service).run(sent.capture());
        assertEquals("case-1", sent.getValue().caseId());
        assertNull(sent.getValue().testCase());
        assertEquals("env-1", sent.getValue().environmentId());
        assertEquals(2, sent.getValue().dataRow());
        assertEquals("Production", sent.getValue().confirmEnvironment());
    }
}
