package dev.juniordev.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
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
    private final ConcurrentHashMap<String, Boolean> flags = new ConcurrentHashMap<>();
    @Value("${feature.flags.admin-token:}") private String adminToken;

    private void validateName(String name) {
        if (!NAME.matcher(name).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid flag name");
    }
    public record FlagValue(String name, boolean enabled) {}
    public record UpdateFlag(Boolean enabled) {}
    @GetMapping("/{name}")
    public FlagValue read(@PathVariable String name) {
        validateName(name);
        return new FlagValue(name, flags.getOrDefault(name, false));
    }
    @PutMapping("/{name}")
    public FlagValue update(@PathVariable String name, @RequestHeader(value="X-Admin-Token",required=false) String supplied, @RequestBody UpdateFlag update) {
        validateName(name);
        if (adminToken.length() < 32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure 32+ character admin token");
        if (supplied == null || !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        if (update == null || update.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled required");
        flags.put(name, update.enabled());
        return new FlagValue(name, update.enabled());
    }
}
