package dev.juniordev.flags;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "feature.flags.admin-token=some-test-token-123456789012345678901234")
class SnapshotControllerTest {
    private static final String TOKEN = "some-test-token-123456789012345678901234";
    @Autowired MockMvc mvc;

    private void set(String name, boolean enabled) throws Exception {
        mvc.perform(put("/api/flags/" + name)
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"))
            .andExpect(status().isOk());
    }

    private String snapshot(String flags) {
        return "{\"version\":1,\"flags\":{" + flags + "}}";
    }

    private String entry(String name, boolean enabled, int rollout) {
        return "\"" + name + "\":{\"enabled\":" + enabled +
                ",\"rolloutPercentage\":" + rollout + "}";
    }

    @Test
    void exportRequiresAdminToken() throws Exception {
        mvc.perform(get("/api/flags/snapshot")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags/snapshot").header("X-Admin-Token", "invalid"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags/snapshot").header("X-Admin-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.flags").isMap());
    }

    @Test
    void snapshotExportsEnabledAndRolloutValues() throws Exception {
        set("snapshot.export", true);
        mvc.perform(put("/api/flags/snapshot.export/rollout")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"percentage\":37}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/flags/snapshot").header("X-Admin-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['flags']['snapshot.export']['enabled']").value(true))
            .andExpect(jsonPath("$['flags']['snapshot.export']['rolloutPercentage']").value(37))
            .andExpect(jsonPath("$['flags']['snapshot.unknown']").doesNotExist());
    }

    @Test
    void previewIsAuthenticatedAndDoesNotMutateFlags() throws Exception {
        String body = snapshot(entry("snapshot.preview", true, 25));
        mvc.perform(post("/api/flags/snapshot/validate")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/flags/snapshot/validate")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.imported").value(1))
            .andExpect(jsonPath("$.names[0]").value("snapshot.preview"));
        mvc.perform(get("/api/flags/snapshot.preview"))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void importRequiresAdminToken() throws Exception {
        String body = snapshot(entry("snapshot.unauthorized", true, 100));
        mvc.perform(post("/api/flags/snapshot/import")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags/snapshot.unauthorized"))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void importMergesWithoutDeletingOtherFlags() throws Exception {
        set("snapshot.keep", true);
        set("snapshot.change", false);
        String body = snapshot(entry("snapshot.change", true, 40) + "," +
                entry("snapshot.new", true, 100));
        mvc.perform(post("/api/flags/snapshot/import")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(2))
            .andExpect(jsonPath("$.names[0]").value("snapshot.change"))
            .andExpect(jsonPath("$.names[1]").value("snapshot.new"));
        mvc.perform(get("/api/flags/snapshot.keep"))
            .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/flags/snapshot.change"))
            .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/flags/snapshot").header("X-Admin-Token", TOKEN))
            .andExpect(jsonPath("$['flags']['snapshot.change']['rolloutPercentage']").value(40));
    }

    @Test
    void invalidEntryRejectsEntireImportWithoutPartialWrites() throws Exception {
        String body = snapshot(entry("snapshot.atomic", true, 80) +
                ",\"INVALID\":{\"enabled\":true,\"rolloutPercentage\":10}");
        mvc.perform(post("/api/flags/snapshot/import")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/flags/snapshot.atomic"))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void rejectsUnsupportedVersionAndEmptySnapshot() throws Exception {
        String valid = snapshot(entry("snapshot.version", true, 100));
        mvc.perform(post("/api/flags/snapshot/import")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(valid.replace("\"version\":1", "\"version\":2")))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/flags/snapshot/import")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"flags\":{}}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/flags/snapshot/validate")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flags\":{}}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingFieldsAndOutOfRangeRollouts() throws Exception {
        String[] invalid = {
            snapshot("\"snapshot.missing\":{\"rolloutPercentage\":20}"),
            snapshot("\"snapshot.missing\":{\"enabled\":true}"),
            snapshot("\"snapshot.null\":null"),
            snapshot(entry("snapshot.low", true, -1)),
            snapshot(entry("snapshot.high", true, 101))
        };
        for (String body : invalid) {
            mvc.perform(post("/api/flags/snapshot/validate")
                    .header("X-Admin-Token", TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void acceptsRolloutBoundaryValues() throws Exception {
        String body = snapshot(entry("snapshot.zero", true, 0) + "," +
                entry("snapshot.hundred", true, 100));
        mvc.perform(post("/api/flags/snapshot/import")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        mvc.perform(get("/api/flags/snapshot.zero/evaluate").param("subject", "test"))
            .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/flags/snapshot.hundred/evaluate").param("subject", "test"))
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void rejectsMoreThanFiveHundredFlags() throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            if (i > 0) entries.append(',');
            entries.append(entry("snapshot.limit." + i, true, 100));
        }
        mvc.perform(post("/api/flags/snapshot/validate")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(snapshot(entries.toString())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void bulkUpdatesAreBoundedToOneHundredFlags() throws Exception {
        StringBuilder entries = new StringBuilder("{");
        for (int i = 0; i < 101; i++) {
            if (i > 0) entries.append(',');
            entries.append("\"bulk.limit.").append(i).append("\":true");
        }
        entries.append('}');
        mvc.perform(post("/api/flags/bulk")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(entries.toString()))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/flags/bulk.limit.0"))
            .andExpect(jsonPath("$.enabled").value(false));
    }
}
