package com.ai.fabric.realapps.chat.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RuntimeVectorSearchController.class)
class RuntimeVectorSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuntimeVectorSearchService searchService;

    @Test
    void delegatesVectorSearchRequestToService() throws Exception {
        RuntimeVectorSearchService.RuntimeVectorSearchResult response =
            new RuntimeVectorSearchService.RuntimeVectorSearchResult(
                "product",
                "wireless headphones",
                3,
                0.0d,
                1,
                List.of(Map.of("entityId", "SKU-0001", "score", 0.91d))
            );
        when(searchService.search("product", "wireless headphones", 3, 0.0d)).thenReturn(response);

        mockMvc.perform(get("/api/runtime/vector-search")
                .param("vectorSpace", "product")
                .param("q", "wireless headphones")
                .param("limit", "3")
                .param("threshold", "0.0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.vectorSpace").value("product"))
            .andExpect(jsonPath("$.returnedResults").value(1))
            .andExpect(jsonPath("$.results[0].entityId").value("SKU-0001"));

        verify(searchService).search("product", "wireless headphones", 3, 0.0d);
    }
}
