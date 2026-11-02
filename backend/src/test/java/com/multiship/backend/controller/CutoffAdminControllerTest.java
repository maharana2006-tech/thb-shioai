package com.multiship.backend.controller;

import com.multiship.backend.model.CutoffRule;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.CutoffRuleRepository;
import com.multiship.backend.repository.HolidayRepository;
import com.multiship.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BUG-092 / 093 / 094 — cutoff rule writes. */
class CutoffAdminControllerTest {

    private CutoffRuleRepository ruleRepo;
    private CutoffAdminController controller;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(CutoffRuleRepository.class);
        controller = new CutoffAdminController(ruleRepo, mock(HolidayRepository.class),
                mock(WarehouseRepository.class), mock(CarrierAccountRefRepository.class));
        when(ruleRepo.save(any(CutoffRule.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CutoffRule rule(long id, String source, String carrier, Long warehouseId, LocalTime time) {
        CutoffRule r = new CutoffRule();
        r.setId(id);
        r.setSource(source);
        r.setCarrierCode(carrier);
        r.setWarehouseId(warehouseId);
        r.setCutoffTime(time);
        r.setActive(true);
        return r;
    }

    @Test
    void aSecondRuleForTheSameKeyIsA409OnTheCarrierField() {
        when(ruleRepo.findAll()).thenReturn(List.of(rule(1L, "BULK", "UPS", 7L, LocalTime.of(18, 0))));
        CutoffRule dup = rule(0L, "bulk", "ups", 7L, LocalTime.of(19, 0));
        dup.setId(null);
        com.multiship.backend.exception.FieldValidationException ex = assertThrows(com.multiship.backend.exception.FieldValidationException.class, () -> controller.createRule(dup, null));
        assertEquals("carrierCode", ex.getField());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void madeUpSourceOrCarrierCodesAreRefused() {
        when(ruleRepo.findAll()).thenReturn(List.of());
        CutoffRule badCarrier = rule(0L, "BULK", "QA2", null, LocalTime.of(18, 0));
        badCarrier.setId(null);
        assertEquals("carrierCode", assertThrows(com.multiship.backend.exception.FieldValidationException.class, () -> controller.createRule(badCarrier, null)).getField());
        CutoffRule badSource = rule(0L, "NOPE", "UPS", null, LocalTime.of(18, 0));
        badSource.setId(null);
        assertEquals("source", assertThrows(com.multiship.backend.exception.FieldValidationException.class, () -> controller.createRule(badSource, null)).getField());
    }

    @Test
    void togglingActiveKeepsTheCutoffTime() {
        CutoffRule existing = rule(5L, "BULK", "UPS", null, LocalTime.of(17, 30));
        when(ruleRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(ruleRepo.findAll()).thenReturn(List.of(existing));
        CutoffAdminController.CutoffRulePatch patch = new CutoffAdminController.CutoffRulePatch();
        patch.setActive(false);

        CutoffRule saved = controller.updateRule(5L, patch, null).getBody().getData();

        assertEquals(LocalTime.of(17, 30), saved.getCutoffTime(), "a toggle must not reset the time to 20:00");
        assertEquals(false, saved.getActive());
    }
}
