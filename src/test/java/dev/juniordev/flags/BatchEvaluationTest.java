package dev.juniordev.flags;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "feature.flags.admin-token=some-test-token-123456789012345678901234")
class BatchEvaluationTest {
    private static final String TOKEN = "some-test-token-123456789012345678901234";
    @Autowired MockMvc mvc;

    private void enable(String name) throws Exception {
        mvc.perform(put("/api/flags/" + name).header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
    }

    @Test
    void batchMatchesSingleEvaluationAndIncludesUnknownFlagsAsDisabled() throws Exception {
        enable("batch.enabled");
        enable("batch.disabled");
        mvc.perform(put("/api/flags/batch.disabled").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":false}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/flags/evaluate").contentType("application/json")
                .content("{\"subject\":\"customer-42\",\"names\":[\"batch.unknown\",\"batch.enabled\",\"batch.disabled\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['batch.enabled']").value(true))
            .andExpect(jsonPath("$['batch.disabled']").value(false))
            .andExpect(jsonPath("$['batch.unknown']").value(false));
        mvc.perform(get("/api/flags/batch.enabled/evaluate").param("subject", "customer-42"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void respectsPercentageRolloutAndKillSwitch() throws Exception {
        enable("batch.rollout");
        mvc.perform(put("/api/flags/batch.rollout/rollout").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"percentage\":0}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/flags/evaluate").contentType("application/json")
                .content("{\"subject\":\"customer-42\",\"names\":[\"batch.rollout\"]}"))
            .andExpect(jsonPath("$['batch.rollout']").value(false));
        mvc.perform(put("/api/flags/batch.rollout/rollout").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"percentage\":100}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/flags/evaluate").contentType("application/json")
                .content("{\"subject\":\"customer-42\",\"names\":[\"batch.rollout\"]}"))
            .andExpect(jsonPath("$['batch.rollout']").value(true));
    }

    @Test
    void invalidRequestsReturnBadRequest() throws Exception {
        String[] invalid = {
            "{}",
            "{\"subject\":\"\",\"names\":[\"valid.flag\"]}",
            "{\"subject\":\"   \",\"names\":[\"valid.flag\"]}",
            "{\"subject\":\"user\",\"names\":[]}",
            "{\"subject\":\"user\",\"names\":[\"BAD\"]}",
            "{\"subject\":\"user\",\"names\":[\"valid.flag\",\"valid.flag\"]}",
            "{\"subject\":\"user\",\"names\":[null]}",
            "{\"subject\":\"user\",\"names\":null}"
        };
        for (String body : invalid) {
            mvc.perform(post("/api/flags/evaluate").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        }
        String longSubject = "x".repeat(257);
        mvc.perform(post("/api/flags/evaluate").contentType("application/json")
                .content("{\"subject\":\"" + longSubject + "\",\"names\":[\"valid.flag\"]}"))
            .andExpect(status().isBadRequest());
        String manyNames = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "\"flag." + i + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/api/flags/evaluate").contentType("application/json")
                .content("{\"subject\":\"user\",\"names\":[" + manyNames + "]}"))
            .andExpect(status().isBadRequest());
    }
}
