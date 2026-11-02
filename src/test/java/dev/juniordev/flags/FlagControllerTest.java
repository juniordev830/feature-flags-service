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
    @Autowired MockMvc mvc;
    @Test void flagsAreFalseByDefaultAndWritesNeedToken() throws Exception {
        mvc.perform(get("/api/flags/demo.feature")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/flags/demo.feature").contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/flags/demo.feature").header("X-Admin-Token", "some-test-token-123456789012345678901234")
            .contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/flags/demo.feature")).andExpect(jsonPath("$.enabled").value(true));
    }
    @Test void rejectsIllegalFlagName() throws Exception {
        mvc.perform(get("/api/flags/UPPER")).andExpect(status().isBadRequest());
    }
}
