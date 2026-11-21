package com.naturalstreaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(name = "ns.role", havingValue = "admin")
public class AdminController {
    private static final String COOKIE = ConsoleAccess.COOKIE;
    private final Sessions sessions;
    private final AssetBank assets;
    private final PresetBank presets;
    private final ConsoleAccess access;
    private final ObjectMapper json = new ObjectMapper();

    AdminController(Sessions sessions, AssetBank assets, PresetBank presets,
                    ConsoleAccess access) {
        this.sessions = sessions;
        this.assets = assets;
        this.presets = presets;
        this.access = access;
    }

    @PostMapping("/session")
    ResponseEntity<Void> login(@RequestHeader(value = "Origin", required = false) String caller,
                               @RequestBody Map<String, String> input) {
        access.checkOrigin(caller);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie(sessions.login("admin", input), 28800)).build();
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> logout(@RequestHeader(value = "Origin", required = false) String caller,
                                @CookieValue(value = COOKIE, required = false) String token) {
        access.checkOrigin(caller);
        sessions.logout("admin", token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).build();
    }

    @GetMapping("/me")
    Object profile(@CookieValue(value = COOKIE, required = false) String token) {
        return access.profile(token);
    }

    @PostMapping("/password")
    ResponseEntity<Void> password(@RequestHeader(value = "Origin", required = false) String caller,
                                  @CookieValue(value = COOKIE, required = false) String token,
                                  @RequestBody Map<String, String> input) {
        access.checkOrigin(caller);
        sessions.changeConsolePassword(token, input);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0)).build();
    }

    @GetMapping("/assets")
    Object assets(@CookieValue(value = COOKIE, required = false) String token) {
        access.actor(token).require("assets.read");
        return assets.list();
    }

    @PostMapping("/assets/import")
    Object upload(@RequestHeader(value = "Origin", required = false) String caller,
                  @CookieValue(value = COOKIE, required = false) String token,
                  @RequestParam("file") MultipartFile file) throws IOException {
        access.write(caller, token, "assets.write");
        try (var contents = file.getInputStream()) {
            return assets.importZip(contents);
        }
    }

    @GetMapping("/assets/export")
    void export(@CookieValue(value = COOKIE, required = false) String token, HttpServletResponse response) throws IOException {
        access.actor(token).require("assets.read");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audio-catalog.zip");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/zip");
        assets.export(response.getOutputStream());
    }

    @GetMapping("/assets/{sha}/audio")
    ResponseEntity<org.springframework.core.io.Resource> audio(@CookieValue(value = COOKIE, required = false) String token,
                                                                    @PathVariable String sha) {
        access.actor(token).require("assets.read");
        return MediaController.resource(assets, sha);
    }

    @GetMapping("/themes")
    Object themes(@CookieValue(value = COOKIE, required = false) String token) {
        access.actor(token).require("themes.read");
        return presets.themes();
    }

    @PostMapping("/themes")
    Object theme(@RequestHeader(value = "Origin", required = false) String caller,
                 @CookieValue(value = COOKIE, required = false) String token, @RequestBody Object input) {
        access.write(caller, token, "themes.write");
        return presets.createTheme(json.valueToTree(input));
    }

    @GetMapping("/presets")
    Object presets(@CookieValue(value = COOKIE, required = false) String token) {
        access.actor(token).require("presets.read");
        return presets.drafts();
    }

    @PostMapping("/presets")
    Object preset(@RequestHeader(value = "Origin", required = false) String caller,
                  @CookieValue(value = COOKIE, required = false) String token, @RequestBody Object input) {
        access.write(caller, token, "presets.write");
        return presets.importPreset(json.valueToTree(input));
    }

    @PostMapping("/presets/{id}/{revision}/publish")
    Object publish(@RequestHeader(value = "Origin", required = false) String caller,
                   @CookieValue(value = COOKIE, required = false) String token,
                   @PathVariable String id, @PathVariable long revision) {
        access.write(caller, token, "presets.publish");
        return presets.publish(id, revision);
    }

    private static String cookie(String token, long seconds) {
        return ResponseCookie.from(COOKIE, token).secure(true).httpOnly(true).sameSite("Strict")
            .path("/").maxAge(seconds).build().toString();
    }
}
