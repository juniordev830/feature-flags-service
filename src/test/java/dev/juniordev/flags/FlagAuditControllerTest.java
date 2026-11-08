package dev.juniordev.flags;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "feature.flags.admin-token=some-test-token-123456789012345678901234")
class FlagAuditControllerTest {
    private static final String TOKEN = "some-test-token-123456789012345678901234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode history(long after, int limit) throws Exception {
        String body = mvc.perform(get("/api/flags/audit")
                    .header("X-Admin-Token", TOKEN)
                    .param("after", Long.toString(after))
                    .param("limit", Integer.toString(limit)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private long cursor() throws Exception {
        JsonNode page = history(0, 100);
        return page.path("nextAfter").asLong();
    }

    private void update(String name, boolean enabled) throws Exception {
        mvc.perform(put("/api/flags/" + name)
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"))
            .andExpect(status().isOk());
    }

    private JsonNode onlyNewEvent(long previous) throws Exception {
        JsonNode page = history(previous, 100);
        assertEquals(1, page.path("events").size(), page.toString());
        return page.path("events").get(0);
    }

    @Test
    void auditEndpointsRequireAdministrativeAuthentication() throws Exception {
        mvc.perform(get("/api/flags/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags/audit").header("X-Admin-Token", "incorrect"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags/audit/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flags/audit/1").header("X-Admin-Token", "incorrect"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidAuditPaginationReturnsBadRequest() throws Exception {
        for (String after : new String[]{"-1", "not-a-number"}) {
            mvc.perform(get("/api/flags/audit").header("X-Admin-Token", TOKEN)
                    .param("after", after))
                .andExpect(status().isBadRequest());
        }
        for (String limit : new String[]{"0", "-1", "101", "not-a-number"}) {
            mvc.perform(get("/api/flags/audit").header("X-Admin-Token", TOKEN)
                    .param("limit", limit))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void unknownAuditEventReturnsNotFound() throws Exception {
        mvc.perform(get("/api/flags/audit/99999999").header("X-Admin-Token", TOKEN))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/flags/audit/0").header("X-Admin-Token", TOKEN))
            .andExpect(status().isNotFound());
    }

    @Test
    void successfulUpdateIsAuditedAndRetrievableById() throws Exception {
        long before = cursor();
        update("audit.single", true);
        JsonNode event = onlyNewEvent(before);
        assertEquals("update", event.path("operation").asText());
        assertEquals(1, event.path("affected").asInt());
        assertEquals("audit.single", event.path("sampleNames").get(0).asText());
        assertFalse(event.path("occurredAt").asText().isBlank());
        mvc.perform(get("/api/flags/audit/" + event.path("id").asLong())
                .header("X-Admin-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operation").value("update"))
            .andExpect(jsonPath("$.sampleNames[0]").value("audit.single"));
    }

    @Test
    void bulkMutationIsOneEventWithSortedNames() throws Exception {
        long before = cursor();
        mvc.perform(post("/api/flags/bulk").header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"audit.bulk.z\":true,\"audit.bulk.a\":false}"))
            .andExpect(status().isOk());
        JsonNode event = onlyNewEvent(before);
        assertEquals("bulk-update", event.path("operation").asText());
        assertEquals(2, event.path("affected").asInt());
        assertEquals("audit.bulk.a", event.path("sampleNames").get(0).asText());
        assertEquals("audit.bulk.z", event.path("sampleNames").get(1).asText());
    }

    @Test
    void rolloutChangesAreRecordedWithoutSubjectIdentifiers() throws Exception {
        update("audit.rollout", true);
        long before = cursor();
        mvc.perform(put("/api/flags/audit.rollout/rollout")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"percentage\":37}"))
            .andExpect(status().isOk());
        JsonNode event = onlyNewEvent(before);
        assertEquals("rollout-update", event.path("operation").asText());
        assertEquals("audit.rollout", event.path("sampleNames").get(0).asText());
        String auditBody = history(before, 10).toString();
        assertFalse(auditBody.contains(TOKEN));
        assertFalse(auditBody.contains("percentage"));
    }

    @Test
    void snapshotImportProducesOneEventForAllImportedFlags() throws Exception {
        long before = cursor();
        String snapshot = """
                {"version":1,"flags":{
                  "audit.snapshot.b":{"enabled":true,"rolloutPercentage":40},
                  "audit.snapshot.a":{"enabled":false,"rolloutPercentage":100}
                }}
                """;
        mvc.perform(post("/api/flags/snapshot/import")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(snapshot))
            .andExpect(status().isOk());
        JsonNode event = onlyNewEvent(before);
        assertEquals("snapshot-import", event.path("operation").asText());
        assertEquals(2, event.path("affected").asInt());
        assertEquals("audit.snapshot.a", event.path("sampleNames").get(0).asText());
        assertEquals("audit.snapshot.b", event.path("sampleNames").get(1).asText());
    }

    @Test
    void deletingExistingFlagIsAuditedButDeletingMissingFlagIsNot() throws Exception {
        update("audit.delete", true);
        long before = cursor();
        mvc.perform(delete("/api/flags/audit.delete").header("X-Admin-Token", TOKEN))
            .andExpect(status().isNoContent());
        JsonNode event = onlyNewEvent(before);
        assertEquals("delete", event.path("operation").asText());
        long after = event.path("id").asLong();
        mvc.perform(delete("/api/flags/audit.delete").header("X-Admin-Token", TOKEN))
            .andExpect(status().isNoContent());
        assertTrue(history(after, 10).path("events").isEmpty());
    }

    @Test
    void rejectedMutationAndSnapshotPreviewDoNotCreateAuditEvents() throws Exception {
        long before = cursor();
        mvc.perform(put("/api/flags/audit.invalid")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/flags/snapshot/validate")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"flags\":{\"audit.preview\":{\"enabled\":true,\"rolloutPercentage\":10}}}"))
            .andExpect(status().isOk());
        assertTrue(history(before, 100).path("events").isEmpty());
    }

    @Test
    void publicEvaluationDoesNotWriteAuditOrExposeSubject() throws Exception {
        long before = cursor();
        mvc.perform(get("/api/flags/audit.unknown/evaluate")
                .param("subject", "pseudonymous-visitor-42"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/flags/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"pseudonymous-visitor-42\",\"names\":[\"audit.unknown\"]}"))
            .andExpect(status().isOk());
        String body = history(before, 100).toString();
        assertFalse(body.contains("pseudonymous-visitor-42"));
        assertTrue(history(before, 100).path("events").isEmpty());
    }

    @Test
    void auditPaginationCanBeResumedWithoutRepeatingEvents() throws Exception {
        long before = cursor();
        update("audit.page.one", true);
        update("audit.page.two", true);
        JsonNode first = history(before, 1);
        assertEquals(1, first.path("events").size());
        assertTrue(first.path("hasMore").asBoolean());
        long next = first.path("nextAfter").asLong();
        JsonNode second = history(next, 1);
        assertEquals(1, second.path("events").size());
        assertFalse(second.path("hasMore").asBoolean());
        assertTrue(second.path("nextAfter").asLong() > next);
    }
}
