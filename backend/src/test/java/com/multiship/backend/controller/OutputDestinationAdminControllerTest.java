package com.multiship.backend.controller;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.service.output.DispatchResult;
import com.multiship.backend.service.output.OutputDestinationAdminService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BUG-188 — a failed test dispatch is a 4xx configuration problem, not a 502 outage. */
class OutputDestinationAdminControllerTest {

    @Test
    void test_failedDispatch_is422WithTheDriverMessage() {
        OutputDestinationAdminService svc = mock(OutputDestinationAdminService.class);
        when(svc.test(13L)).thenReturn(DispatchResult.builder()
                .totalDestinations(1).successCount(0).failureCount(1)
                .items(List.of(DispatchResult.Item.builder().destinationId(13L).success(false)
                        .failureMessage("LOCAL_FS path is not an existing directory: /tmp/qa-od1").build()))
                .build());

        var resp = new OutputDestinationAdminController(svc).test(13L);

        assertEquals(422, resp.getStatusCode().value());
        assertEquals(ErrorCode.OUTPUT_DELIVERY_FAILED.name(), resp.getBody().getErrorCode());
        assertTrue(resp.getBody().getMessage().contains("not an existing directory"), resp.getBody().getMessage());
    }
}
