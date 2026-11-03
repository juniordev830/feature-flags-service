package dev.juniordev.flags;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "feature.flags.admin-token=some-test-token-123456789012345678901234")
class RolloutControllerTest {
    private static final String TOKEN = "some-test-token-123456789012345678901234";
    @Autowired MockMvc mvc;

    @Test
    void zeroAndFullRolloutAndKillSwitch() throws Exception {
        mvc.perform(put("/api/flags/rollout.demo").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/flags/rollout.demo/rollout").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"percentage\":0}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/flags/rollout.demo/evaluate").param("subject", "user-123"))
            .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/flags/rollout.demo/rollout").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"percentage\":100}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/flags/rollout.demo/evaluate").param("subject", "user-123"))
            .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(put("/api/flags/rollout.demo").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":false}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/flags/rollout.demo/evaluate").param("subject", "user-123"))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void sameSubjectAlwaysGetsSameCohortAndBulkUpdatePreservesRollout() throws Exception {
        mvc.perform(put("/api/flags/cohort.demo").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/flags/cohort.demo/rollout").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"percentage\":25}"))
            .andExpect(status().isOk());
        String first = mvc.perform(get("/api/flags/cohort.demo/evaluate")
                .param("subject", "same-user")).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get("/api/flags/cohort.demo/evaluate")
                .param("subject", "same-user")).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        mvc.perform(post("/api/flags/bulk").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"cohort.demo\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/flags/cohort.demo/rollout").header("X-Admin-Token", TOKEN))
            .andExpect(jsonPath("$.percentage").value(25));
        mvc.perform(delete("/api/flags/cohort.demo").header("X-Admin-Token", TOKEN))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/flags/cohort.demo/rollout").header("X-Admin-Token", TOKEN))
            .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnauthorizedOrInvalidRolloutChanges() throws Exception {
        mvc.perform(get("/api/flags/never-created/rollout")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/flags/never-created/rollout").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"percentage\":20}"))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/flags/valid.demo").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/flags/valid.demo/rollout")
                .contentType("application/json").content("{\"percentage\":20}"))
            .andExpect(status().isUnauthorized());
        for (String invalid : new String[]{"-1", "101", "null"}) {
            mvc.perform(put("/api/flags/valid.demo/rollout").header("X-Admin-Token", TOKEN)
                    .contentType("application/json").content("{\"percentage\":" + invalid + "}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/flags/valid.demo/rollout").header("X-Admin-Token", TOKEN))
            .andExpect(jsonPath("$.percentage").value(100));
        mvc.perform(get("/api/flags/valid.demo/evaluate").param("subject", " "))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/flags/valid.demo/evaluate").param("subject", "x".repeat(257)))
            .andExpect(status().isBadRequest());
    }
}
