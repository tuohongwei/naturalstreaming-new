package com.naturalstreaming;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ConsoleDirectoryTest {
    private final ConsoleDirectory directory = new ConsoleDirectory(new JdbcTemplate());

    @Test
    void forbidsGrantingPermissionsTheEditorDoesNotHave() {
        var denied = assertThrows(ResponseStatusException.class, () -> directory.createRole(
                Map.of("name", "Delegate", "permissions", List.of("assets.read", "presets.publish")),
                Set.of("assets.read", "roles.manage")));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
    }

    @Test
    void rejectsUnknownPermissions() {
        var denied = assertThrows(ResponseStatusException.class, () -> directory.createRole(
                Map.of("name", "Delegate", "permissions", List.of("all")), Set.of("roles.manage")));
        assertEquals(HttpStatus.BAD_REQUEST, denied.getStatusCode());
    }

    @Test
    void enforcesPasswordLimitInUtf8Bytes() {
        assertThrows(ResponseStatusException.class, () -> ConsoleDirectory.validatePassword("短码"));
        assertThrows(ResponseStatusException.class, () -> ConsoleDirectory.validatePassword("例".repeat(25)));
        assertDoesNotThrow(() -> ConsoleDirectory.validatePassword("例".repeat(24)));
        assertDoesNotThrow(() -> ConsoleDirectory.validatePassword("sample123"));
    }
}
