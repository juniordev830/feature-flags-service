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
class FlagControllerTest {
    private static final String TOKEN = "some-test-token-123456789012345678901234";
    @Autowired MockMvc mvc;

    @Test
    void flagsAreFalseByDefaultAndWritesNeedToken() throws Exception {
        mvc.perform(get("/api/flags/demo.feature"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/flags/demo.feature").contentType("application/json")
                .content("{\"enabled\":true}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/flags/demo.feature").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/flags/demo.feature"))
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void rejectsIllegalFlagName() throws Exception {
        mvc.perform(get("/api/flags/UPPER")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/flags/UPPER").header("X-Admin-Token", TOKEN))
            .andExpect(status().isBadRequest());
    }

    @Test
    void inventoryRequiresAdminAuthenticationAndListsConfiguredFlags() throws Exception {
        mvc.perform(get("/api/flags")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags").header("X-Admin-Token", "incorrect"))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/flags/ops.toggle").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/flags").header("X-Admin-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['ops.toggle']").value(true))
            .andExpect(jsonPath("$['unknown.flag']").doesNotExist());
    }

    @Test
    void bulkUpdatesValidateBeforeChangingAnyFlag() throws Exception {
        mvc.perform(post("/api/flags/bulk").header("X-Admin-Token", TOKEN)
                .contentType("application/json")
                .content("{\"bulk.one\":true,\"bulk.two\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['bulk.one']").value(true))
            .andExpect(jsonPath("$['bulk.two']").value(false));

        mvc.perform(post("/api/flags/bulk").header("X-Admin-Token", TOKEN)
                .contentType("application/json")
                .content("{\"bulk.three\":true,\"INVALID\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/flags/bulk.three"))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void deletingFlagRequiresAdminAndResetsItToDisabled() throws Exception {
        mvc.perform(put("/api/flags/reset.demo").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/flags/reset.demo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags/reset.demo"))
            .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(delete("/api/flags/reset.demo").header("X-Admin-Token", TOKEN))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/flags/reset.demo"))
            .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/flags").header("X-Admin-Token", TOKEN))
            .andExpect(jsonPath("$['reset.demo']").doesNotExist());
    }
}
