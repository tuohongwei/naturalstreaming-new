package com.naturalstreaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(name = "ns.role", havingValue = "admin")
public class AdminController {
    private static final String COOKIE = "__Host-ns-console";
    private final Sessions sessions;
    private final AssetBank assets;
    private final PresetBank presets;
    private final String origin;
    private final ObjectMapper json = new ObjectMapper();

    AdminController(Sessions sessions, AssetBank assets, PresetBank presets,
                    @Value("${ns.console-origin}") String origin) {
        this.sessions = sessions;
        this.assets = assets;
        this.presets = presets;
        this.origin = origin;
    }

    @PostMapping("/session")
    ResponseEntity<Void> login(@RequestHeader(value = "Origin", required = false) String caller,
                               @RequestBody Map<String, String> input) {
        checkOrigin(caller);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie(sessions.login("admin", input), 28800)).build();
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> logout(@RequestHeader(value = "Origin", required = false) String caller,
                                @CookieValue(value = COOKIE, required = false) String token) {
        checkOrigin(caller);
        sessions.logout("admin", token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).build();
    }

    @GetMapping("/assets")
    Object assets(@CookieValue(value = COOKIE, required = false) String token) {
        sessions.require("admin", token);
        return assets.list();
    }

    @PostMapping("/assets/import")
    Object upload(@RequestHeader(value = "Origin", required = false) String caller,
                  @CookieValue(value = COOKIE, required = false) String token,
                  @RequestParam("file") MultipartFile file) throws IOException {
        authorizeWrite(caller, token);
        try (var contents = file.getInputStream()) {
            return assets.importZip(contents);
        }
    }

    @GetMapping("/assets/export")
    void export(@CookieValue(value = COOKIE, required = false) String token, HttpServletResponse response) throws IOException {
        sessions.require("admin", token);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audio-catalog.zip");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/zip");
        assets.export(response.getOutputStream());
    }

    @GetMapping("/assets/{sha}/audio")
    ResponseEntity<org.springframework.core.io.Resource> audio(@CookieValue(value = COOKIE, required = false) String token,
                                                                    @PathVariable String sha) {
        sessions.require("admin", token);
        return MediaController.resource(assets, sha);
    }

    @GetMapping("/themes")
    Object themes(@CookieValue(value = COOKIE, required = false) String token) {
        sessions.require("admin", token);
        return presets.themes();
    }

    @PostMapping("/themes")
    Object theme(@RequestHeader(value = "Origin", required = false) String caller,
                 @CookieValue(value = COOKIE, required = false) String token, @RequestBody Object input) {
        authorizeWrite(caller, token);
        return presets.createTheme(json.valueToTree(input));
    }

    @GetMapping("/presets")
    Object presets(@CookieValue(value = COOKIE, required = false) String token) {
        sessions.require("admin", token);
        return presets.drafts();
    }

    @PostMapping("/presets")
    Object preset(@RequestHeader(value = "Origin", required = false) String caller,
                  @CookieValue(value = COOKIE, required = false) String token, @RequestBody Object input) {
        authorizeWrite(caller, token);
        return presets.importPreset(json.valueToTree(input));
    }

    @PostMapping("/presets/{id}/{revision}/publish")
    Object publish(@RequestHeader(value = "Origin", required = false) String caller,
                   @CookieValue(value = COOKIE, required = false) String token,
                   @PathVariable String id, @PathVariable long revision) {
        authorizeWrite(caller, token);
        return presets.publish(id, revision);
    }

    private void authorizeWrite(String caller, String token) {
        checkOrigin(caller);
        sessions.require("admin", token);
    }

    private void checkOrigin(String caller) {
        if (!origin.equals(caller)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid_origin");
    }

    private static String cookie(String token, long seconds) {
        return ResponseCookie.from(COOKIE, token).secure(true).httpOnly(true).sameSite("Strict")
            .path("/").maxAge(seconds).build().toString();
    }
}
