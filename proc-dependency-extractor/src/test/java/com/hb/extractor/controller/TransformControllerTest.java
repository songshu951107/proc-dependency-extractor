package com.hb.extractor.controller;

import com.hb.extractor.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "transform.base-dir=target/.tmp_transform_it")
class TransformControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;

    private String token() {
        return "Bearer " + jwtUtil.generate(1L, "tester", "ADMIN");
    }

    /** 每次测试前清空 input/output，保证 listEmptyDir 等用例可重复运行 */
    @BeforeEach
    void cleanBaseDir() throws Exception {
        Path base = Path.of("target/.tmp_transform_it");
        deleteRecursively(base.resolve("input"));
        deleteRecursively(base.resolve("output"));
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(f -> {
                        try { Files.deleteIfExists(f); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    });
        }
    }

    @Test
    void listEmptyDir() throws Exception {
        mvc.perform(get("/api/transform/scripts").header("Authorization", token()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void uploadPyThenListPending() throws Exception {
        MockMultipartFile f = new MockMultipartFile(
                "file", "dwa_demo.py", "text/plain", "#更新对象 X\n".getBytes());
        mvc.perform(multipart("/api/transform/upload").file(f).header("Authorization", token()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("dwa_demo.py")));
        mvc.perform(get("/api/transform/scripts").header("Authorization", token()))
           .andExpect(jsonPath("$.data[0].status").value("PENDING"));
        Files.deleteIfExists(Path.of("target/.tmp_transform_it/input/dwa_demo.py"));
    }

    @Test
    void uploadRejectsNonPy() throws Exception {
        MockMultipartFile f = new MockMultipartFile(
                "file", "evil.sh", "text/plain", "rm -rf".getBytes());
        mvc.perform(multipart("/api/transform/upload").file(f).header("Authorization", token()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void pathTraversalRejected() throws Exception {
        mvc.perform(get("/api/transform/scripts/..%2Fetc/report").header("Authorization", token()))
           .andExpect(jsonPath("$.code").value(400));
    }
}
