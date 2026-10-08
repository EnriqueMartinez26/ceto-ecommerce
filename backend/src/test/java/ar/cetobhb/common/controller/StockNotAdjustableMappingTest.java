package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.cetobhb.inventory.domain.StockNotAdjustableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.event.Level;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** AINV-04, ERR-08: adjusting the stock of a combo or of a product without a stock row is a conflict, logged tersely. */
@ExtendWith(OutputCaptureExtension.class)
class StockNotAdjustableMappingTest {

    private MockMvc mvc;

    @RestController
    static class Boom {
        @GetMapping("/boom")
        void boom() {
            throw new StockNotAdjustableException(7L);
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Boom()).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void itIsAFixedConflictWithoutAnyFigureOrName() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("The request conflicts with the current state."))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.productIds").doesNotExist());
    }

    @Test
    void itIsLoggedAtWarnWithTheClassMessageAndTheProductIdOnly(CapturedOutput output) throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isConflict());

        assertThat(ApiErrorCatalog.domainErrors().get(StockNotAdjustableException.class).logLevel())
                .isEqualTo(Level.WARN);
        assertThat(output.getAll()).containsPattern("WARN.*CONFLICT STOCK_NOT_ADJUSTABLE\\(7\\)");
    }
}
