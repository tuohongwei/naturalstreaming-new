package com.naturalstreaming;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(name = "ns.role", havingValue = "admin")
class ConsoleDirectoryController {
    private static final String COOKIE = ConsoleAccess.COOKIE;
    private static final String ORIGIN = "Origin";
    private final ConsoleAccess access;
    private final ConsoleDirectory directory;

    ConsoleDirectoryController(ConsoleAccess access, ConsoleDirectory directory) {
        this.access = access;
        this.directory = directory;
    }

    @GetMapping("/roles")
    Object roles(@CookieValue(value = COOKIE, required = false) String token) {
        var actor = access.actor(token);
        if (!actor.permissions().contains("accounts.manage")) actor.require("roles.manage");
        return directory.roles();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PostMapping("/roles")
    Object createRole(@CookieValue(value = COOKIE, required = false) String token,
                      @RequestHeader(value = ORIGIN, required = false) String origin,
                      @RequestBody Map<String, Object> input) {
        return directory.createRole(input, administration(origin, token, "roles.manage").permissions());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PutMapping("/roles/{id}")
    Object updateRole(@CookieValue(value = COOKIE, required = false) String token,
                      @RequestHeader(value = ORIGIN, required = false) String origin,
                      @PathVariable long id, @RequestBody Map<String, Object> input) {
        return directory.updateRole(id, input, administration(origin, token, "roles.manage").permissions());
    }

    @GetMapping("/{collection:accounts|members}")
    Object accounts(@CookieValue(value = COOKIE, required = false) String token, @PathVariable String collection) {
        access.actor(token).require(collection + ".manage");
        return directory.accounts(kind(collection));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PostMapping("/{collection:accounts|members}")
    Object createAccount(@CookieValue(value = COOKIE, required = false) String token,
                         @RequestHeader(value = ORIGIN, required = false) String origin,
                         @PathVariable String collection, @RequestBody Map<String, Object> input) {
        return directory.createAccount(kind(collection), input, administration(origin, token, collection + ".manage").permissions());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PutMapping("/{collection:accounts|members}/{id}")
    Object updateAccount(@CookieValue(value = COOKIE, required = false) String token,
                         @RequestHeader(value = ORIGIN, required = false) String origin,
                         @PathVariable String collection, @PathVariable long id, @RequestBody Map<String, Object> input) {
        return directory.updateAccount(kind(collection), id, input, administration(origin, token, collection + ".manage").permissions());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PostMapping("/members/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetMemberPassword(@CookieValue(value = COOKIE, required = false) String token,
                             @RequestHeader(value = ORIGIN, required = false) String origin,
                             @PathVariable long id, @RequestBody Map<String, Object> input) {
        administration(origin, token, "members.manage");
        directory.resetMemberPassword(id, input);
    }

    private ConsoleAccess.Actor administration(String origin, String token, String permission) {
        access.write(origin, token, permission);
        directory.lockAdministration();
        return access.actor(token).require(permission);
    }

    private static String kind(String collection) {
        return switch (collection) {
            case "accounts" -> "admin";
            case "members" -> "app";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }
}
