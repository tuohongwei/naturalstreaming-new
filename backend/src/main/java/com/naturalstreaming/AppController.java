package com.naturalstreaming;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(name = "ns.role", havingValue = "api")
public class AppController {
    private final Sessions sessions;
    private final PresetBank presets;

    AppController(Sessions sessions, PresetBank presets) {
        this.sessions = sessions;
        this.presets = presets;
    }

    @PostMapping("/auth/login")
    Map<String, String> login(@RequestBody Map<String, String> input) {
        return Map.of("access_token", sessions.login("app", input), "token_type", "Bearer");
    }

    @GetMapping("/me")
    Map<String, String> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        require(authorization);
        return Map.of("status", "active");
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        sessions.logout("app", HttpResults.bearer(authorization));
    }

    @GetMapping("/presets")
    Object catalog(@RequestHeader(value = "Authorization", required = false) String authorization) {
        require(authorization);
        return presets.catalog();
    }

    @GetMapping("/presets/{id}/versions/{revision}")
    Object version(@RequestHeader(value = "Authorization", required = false) String authorization,
                   @PathVariable String id, @PathVariable long revision) {
        require(authorization);
        return presets.version(id, revision);
    }

    private void require(String authorization) {
        sessions.require("app", HttpResults.bearer(authorization));
    }
}
