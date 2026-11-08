package dev.juniordev.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/flags")
public class FlagController {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private record Configuration(boolean enabled, int rolloutPercentage) {}
    private final ConcurrentHashMap<String, Configuration> flags = new ConcurrentHashMap<>();
    @Value("${feature.flags.admin-token:}") private String adminToken;

    private void validateName(String name) {
        if (!NAME.matcher(name).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid flag name");
    }

    private void requireAdminToken(String supplied) {
        if (adminToken.length() < 32)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure 32+ character admin token");
        if (supplied == null ||
                !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
                                       supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    public record FlagValue(String name, boolean enabled) {}
    public record UpdateFlag(Boolean enabled) {}
    public record UpdateRollout(Integer percentage) {}
    public record RolloutValue(String name, int percentage) {}

    @GetMapping("/{name}")
    public FlagValue read(@PathVariable String name) {
        validateName(name);
        Configuration config = flags.get(name);
        return new FlagValue(name, config != null && config.enabled());
    }

    /** Only explicitly configured flags are listed; unknown flags default to false. */
    @GetMapping
    public Map<String, Boolean> list(@RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        requireAdminToken(supplied);
        var listed = new TreeMap<String, Boolean>();
        flags.forEach((name, config) -> listed.put(name, config.enabled()));
        return listed;
    }

    /**
     * Applies a validated batch so a deployment can update several flags in one request.
     * Validation runs before the map is changed, preventing partial writes.
     */
    @PostMapping("/bulk")
    public Map<String, Boolean> bulkUpdate(
            @RequestHeader(value = "X-Admin-Token", required = false) String supplied,
            @RequestBody Map<String, Boolean> updates) {
        requireAdminToken(supplied);
        if (updates == null || updates.isEmpty() || updates.size() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1..100 flags");
        var validated = new TreeMap<String, Boolean>();
        updates.forEach((name, enabled) -> {
            if (name == null || !NAME.matcher(name).matches() || enabled == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bulk flag update");
            validated.put(name, enabled);
        });
        validated.forEach((name, enabled) -> flags.compute(name,
                (key, existing) -> new Configuration(enabled, existing == null ? 100 : existing.rolloutPercentage())));
        return validated;
    }

    @PutMapping("/{name}")
    public FlagValue update(@PathVariable String name,
                            @RequestHeader(value = "X-Admin-Token", required = false) String supplied,
                            @RequestBody UpdateFlag update) {
        validateName(name);
        requireAdminToken(supplied);
        if (update == null || update.enabled() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled required");
        flags.compute(name, (key, existing) -> new Configuration(
                update.enabled(), existing == null ? 100 : existing.rolloutPercentage()));
        return new FlagValue(name, update.enabled());
    }

    /**
     * Evaluates a percentage rollout consistently for the same flag/subject pair.
     * Rollouts are release controls, NOT authentication or authorization.
     */
    @GetMapping("/{name}/evaluate")
    public FlagValue evaluate(@PathVariable String name, @RequestParam String subject) {
        validateName(name);
        if (subject.isBlank() || subject.length() > 256)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject must contain 1..256 characters");
        Configuration config = flags.get(name);
        boolean enabled = config != null && config.enabled() &&
                RolloutPolicy.includes(name, subject, config.rolloutPercentage());
        return new FlagValue(name, enabled);
    }

    /**
     * Evaluate a single subject against multiple flags in one network request.
     * No configured-flag inventory is exposed: only requested names are returned.
     * Invalid requests are rejected before evaluating any flag.
     */
    public record BatchEvaluationRequest(String subject, java.util.List<String> names) {}

    @PostMapping("/evaluate")
    public Map<String, Boolean> evaluateBatch(@RequestBody BatchEvaluationRequest request) {
        if (request == null || request.subject() == null ||
                request.subject().isBlank() || request.subject().length() > 256)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject must contain 1..256 characters");
        if (request.names() == null || request.names().isEmpty() || request.names().size() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1..100 flag names");
        var names = new java.util.TreeSet<String>();
        for (String name : request.names()) {
            if (name == null || !NAME.matcher(name).matches() || !names.add(name))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or duplicate flag name");
        }
        var result = new TreeMap<String, Boolean>();
        for (String name : names) {
            Configuration config = flags.get(name);
            result.put(name, config != null && config.enabled() &&
                    RolloutPolicy.includes(name, request.subject(), config.rolloutPercentage()));
        }
        return result;
    }

    @GetMapping("/{name}/rollout")
    public RolloutValue readRollout(
            @PathVariable String name,
            @RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        validateName(name);
        requireAdminToken(supplied);
        Configuration config = flags.get(name);
        if (config == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flag is not configured");
        return new RolloutValue(name, config.rolloutPercentage());
    }

    /** A rollout is configured only after the flag has been explicitly created. */
    @PutMapping("/{name}/rollout")
    public RolloutValue updateRollout(
            @PathVariable String name,
            @RequestHeader(value = "X-Admin-Token", required = false) String supplied,
            @RequestBody UpdateRollout update) {
        validateName(name);
        requireAdminToken(supplied);
        if (update == null || update.percentage() == null ||
                update.percentage() < 0 || update.percentage() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "percentage must be 0..100");
        Configuration config = flags.computeIfPresent(name,
                (key, existing) -> new Configuration(existing.enabled(), update.percentage()));
        if (config == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flag is not configured");
        return new RolloutValue(name, config.rolloutPercentage());
    }


    /**
     * Versioned snapshots support backups and controlled promotion between environments.
     * The export includes only explicitly configured flags and requires admin access.
     * Import is a merge: flags not present in the snapshot remain unchanged.
     */
    public record SnapshotFlag(Boolean enabled, Integer rolloutPercentage) {}
    public record SnapshotImportRequest(Integer version, Map<String, SnapshotFlag> flags) {}
    public record SnapshotExport(int version, Map<String, SnapshotFlag> flags) {}
    public record SnapshotImportResult(int version, int imported, java.util.List<String> names) {}

    @GetMapping("/snapshot")
    public SnapshotExport exportSnapshot(
            @RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        requireAdminToken(supplied);
        var result = new TreeMap<String, SnapshotFlag>();
        flags.forEach((name, config) ->
                result.put(name, new SnapshotFlag(config.enabled(), config.rolloutPercentage())));
        return new SnapshotExport(1, result);
    }

    /**
     * Validate the complete snapshot before modifying any configuration.
     * A maximum of 500 flags prevents accidental unbounded administrative imports.
     */
    private TreeMap<String, Configuration> validateSnapshot(SnapshotImportRequest request) {
        if (request == null || request.version() == null || request.version() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported snapshot version");
        if (request.flags() == null || request.flags().isEmpty() || request.flags().size() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1..500 snapshot flags");
        var validated = new TreeMap<String, Configuration>();
        request.flags().forEach((name, value) -> {
            if (name == null || !NAME.matcher(name).matches() ||
                    value == null || value.enabled() == null ||
                    value.rolloutPercentage() == null ||
                    value.rolloutPercentage() < 0 || value.rolloutPercentage() > 100)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot flag");
            validated.put(name, new Configuration(value.enabled(), value.rolloutPercentage()));
        });
        return validated;
    }

    /**
     * Dry-run an import using exactly the same validation as the real import.
     * The response lists the names that would be updated, without changing state.
     */
    @PostMapping("/snapshot/validate")
    public SnapshotImportResult previewSnapshot(
            @RequestHeader(value = "X-Admin-Token", required = false) String supplied,
            @RequestBody SnapshotImportRequest request) {
        requireAdminToken(supplied);
        var validated = validateSnapshot(request);
        return new SnapshotImportResult(1, validated.size(), java.util.List.copyOf(validated.keySet()));
    }

    /**
     * Merge a validated snapshot. Validation is all-or-nothing, but simultaneous
     * administrative writes are not transactionally isolated across multiple flags.
     */
    @PostMapping("/snapshot/import")
    public SnapshotImportResult importSnapshot(
            @RequestHeader(value = "X-Admin-Token", required = false) String supplied,
            @RequestBody SnapshotImportRequest request) {
        requireAdminToken(supplied);
        var validated = validateSnapshot(request);
        flags.putAll(validated);
        return new SnapshotImportResult(1, validated.size(), java.util.List.copyOf(validated.keySet()));
    }

    /** Removing a flag restores its default disabled state. */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name,
                       @RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        validateName(name);
        requireAdminToken(supplied);
        flags.remove(name);
    }
}
