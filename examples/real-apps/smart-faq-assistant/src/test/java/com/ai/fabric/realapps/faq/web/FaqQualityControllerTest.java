package com.ai.fabric.realapps.faq.web;

import com.ai.fabric.realapps.faq.service.FaqDemoCatalog;
import com.ai.fabric.realapps.faq.service.FaqQualityService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaqQualityControllerTest {

    private final FaqQualityService qualityService = mock(FaqQualityService.class);
    private final FaqQualityController controller = new FaqQualityController(qualityService);

    @Test
    void returnsConfiguredGoldenQuestions() {
        List<FaqDemoCatalog.GoldenQuestion> questions = FaqDemoCatalog.goldenQuestions();
        when(qualityService.goldenQuestions()).thenReturn(questions);

        assertThat(controller.goldenQuestions()).isEqualTo(questions);
    }

    @Test
    void runsGoldenSetWithRequestOptions() {
        FaqQualityService.QualityRunOptions options = new FaqQualityService.QualityRunOptions(5, 0.1d, true, false);
        FaqQualityService.QualityReport report = new FaqQualityService.QualityReport(
            true,
            "LOCAL_RETRIEVAL_GOLDEN_SET",
            0,
            0,
            0,
            1.0d,
            5,
            0.1d,
            true,
            false,
            "DISABLED",
            List.of()
        );
        when(qualityService.runGoldenSet(options)).thenReturn(report);

        assertThat(controller.runGoldenQuestions(options)).isSameAs(report);
        verify(qualityService).runGoldenSet(options);
    }
}
