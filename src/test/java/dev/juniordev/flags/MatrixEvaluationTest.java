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
class MatrixEvaluationTest {
    private static final String TOKEN = "some-test-token-123456789012345678901234";
    @Autowired MockMvc mvc;

    private void enable(String name) throws Exception {
        mvc.perform(put("/api/flags/" + name)
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isOk());
    }

    private void rollout(String name, int percentage) throws Exception {
        mvc.perform(put("/api/flags/" + name + "/rollout")
                .header("X-Admin-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"percentage\":" + percentage + "}"))
            .andExpect(status().isOk());
    }

    private String body(String subjects, String names) {
        return "{\"subjects\":" + subjects + ",\"names\":" + names + "}";
    }

    @Test
    void matrixReturnsSortedDecisionsForSeveralSubjects() throws Exception {
        enable("matrix.enabled");
        enable("matrix.disabled");
        rollout("matrix.disabled", 0);
        mvc.perform(post("/api/flags/evaluate/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("[\"z-user\",\"a-user\"]",
                              "[\"matrix.unknown\",\"matrix.enabled\",\"matrix.disabled\"]")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['a-user']['matrix.enabled']").value(true))
            .andExpect(jsonPath("$['a-user']['matrix.disabled']").value(false))
            .andExpect(jsonPath("$['a-user']['matrix.unknown']").value(false))
            .andExpect(jsonPath("$['z-user']['matrix.enabled']").value(true));
    }

    @Test
    void matrixMatchesSingleSubjectEvaluationAtPercentageBoundary() throws Exception {
        enable("matrix.hundred");
        enable("matrix.zero");
        rollout("matrix.hundred", 100);
        rollout("matrix.zero", 0);
        mvc.perform(post("/api/flags/evaluate/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("[\"subject-1\",\"subject-2\"]",
                              "[\"matrix.hundred\",\"matrix.zero\"]")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['subject-1']['matrix.hundred']").value(true))
            .andExpect(jsonPath("$['subject-2']['matrix.hundred']").value(true))
            .andExpect(jsonPath("$['subject-1']['matrix.zero']").value(false))
            .andExpect(jsonPath("$['subject-2']['matrix.zero']").value(false));
    }

    @Test
    void rejectsEmptyOrMissingDimensions() throws Exception {
        String[] invalid = {
            "{}",
            "{\"subjects\":[],\"names\":[\"matrix.enabled\"]}",
            "{\"subjects\":[\"user\"],\"names\":[]}",
            "{\"subjects\":null,\"names\":[\"matrix.enabled\"]}",
            "{\"subjects\":[\"user\"],\"names\":null}"
        };
        for (String body : invalid) {
            mvc.perform(post("/api/flags/evaluate/matrix")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsDuplicateOrInvalidSubjects() throws Exception {
        String[] invalidSubjects = {
            "[\"same\",\"same\"]",
            "[\"\"]",
            "[\"   \"]",
            "[null]",
            "[\"" + "a".repeat(257) + "\"]"
        };
        for (String subjects : invalidSubjects) {
            mvc.perform(post("/api/flags/evaluate/matrix")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(subjects, "[\"matrix.enabled\"]")))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsDuplicateOrInvalidFlagNames() throws Exception {
        String[] invalidNames = {
            "[\"matrix.enabled\",\"matrix.enabled\"]",
            "[\"UPPER\"]",
            "[null]",
            "[\"\"]"
        };
        for (String names : invalidNames) {
            mvc.perform(post("/api/flags/evaluate/matrix")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("[\"subject\"]", names)))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsTooManySubjects() throws Exception {
        StringBuilder subjects = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) subjects.append(',');
            subjects.append("\"subject-").append(i).append("\"");
        }
        subjects.append(']');
        mvc.perform(post("/api/flags/evaluate/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(subjects.toString(), "[\"matrix.enabled\"]")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsTooManyFlags() throws Exception {
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) names.append(',');
            names.append("\"matrix.").append(i).append("\"");
        }
        names.append(']');
        mvc.perform(post("/api/flags/evaluate/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("[\"subject\"]", names.toString())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCartesianProductOverOneThousand() throws Exception {
        StringBuilder subjects = new StringBuilder("[");
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) subjects.append(',');
            subjects.append("\"subject-").append(i).append("\"");
        }
        for (int i = 0; i < 26; i++) {
            if (i > 0) names.append(',');
            names.append("\"matrix.").append(i).append("\"");
        }
        subjects.append(']');
        names.append(']');
        mvc.perform(post("/api/flags/evaluate/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(subjects.toString(), names.toString())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsExactlyOneThousandEvaluations() throws Exception {
        StringBuilder subjects = new StringBuilder("[");
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) subjects.append(',');
            subjects.append("\"subject-").append(i).append("\"");
        }
        for (int i = 0; i < 25; i++) {
            if (i > 0) names.append(',');
            names.append("\"matrix.").append(i).append("\"");
        }
        subjects.append(']');
        names.append(']');
        mvc.perform(post("/api/flags/evaluate/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(subjects.toString(), names.toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['subject-0']['matrix.0']").value(false))
            .andExpect(jsonPath("$['subject-39']['matrix.24']").value(false));
    }
}
