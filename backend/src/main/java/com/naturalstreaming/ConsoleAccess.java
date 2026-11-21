package com.naturalstreaming;

import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "ns.role", havingValue = "admin")
class ConsoleAccess {
    static final String COOKIE = "__Host-ns-console";

    record Actor(String username, Set<String> permissions) {
        Actor require(String permission) {
            if (!permissions.contains(permission)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "permission_denied");
            }
            return this;
        }
    }

    private final Sessions sessions;
    private final ConsoleDirectory directory;
    private final String origin;

    ConsoleAccess(Sessions sessions, ConsoleDirectory directory, @Value("${ns.console-origin}") String origin) {
        this.sessions = sessions;
        this.directory = directory;
        this.origin = origin;
    }

    Actor actor(String token) {
        var principal = sessions.require("admin", token);
        return new Actor(principal.username(), directory.permissions(principal.id()));
    }

    Map<String, Object> profile(String token) {
        var actor = actor(token);
        return Map.of("username", actor.username(), "permissions", actor.permissions());
    }

    Actor write(String caller, String token, String permission) {
        checkOrigin(caller);
        return actor(token).require(permission);
    }

    void checkOrigin(String caller) {
        if (!origin.equals(caller)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid_origin");
    }
}
