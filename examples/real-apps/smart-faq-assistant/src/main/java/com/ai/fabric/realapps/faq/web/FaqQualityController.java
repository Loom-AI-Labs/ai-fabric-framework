package com.ai.fabric.realapps.faq.web;

import com.ai.fabric.realapps.faq.service.FaqDemoCatalog;
import com.ai.fabric.realapps.faq.service.FaqQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/faq/quality")
@RequiredArgsConstructor
public class FaqQualityController {

    private final FaqQualityService faqQualityService;

    @GetMapping("/golden")
    public List<FaqDemoCatalog.GoldenQuestion> goldenQuestions() {
        return faqQualityService.goldenQuestions();
    }

    @PostMapping("/golden/run")
    public FaqQualityService.QualityReport runGoldenQuestions(
        @RequestBody(required = false) FaqQualityService.QualityRunOptions options
    ) {
        return faqQualityService.runGoldenSet(options);
    }
}
