package com.hb.extractor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LineageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGetLineageNotFound() throws Exception {
        mockMvc.perform(get("/api/lineage/non_existent_proc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("存储过程不存在"));
    }

    @Test
    void testMaxDepthValidation() throws Exception {
        mockMvc.perform(get("/api/lineage/table/SOME_TABLE/upstream").param("maxDepth", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("maxDepth 不能超过 10"));
    }

    @Test
    void testUpstreamEmptyResult() throws Exception {
        mockMvc.perform(get("/api/lineage/table/NON_EXISTENT/upstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").exists());
    }
}
