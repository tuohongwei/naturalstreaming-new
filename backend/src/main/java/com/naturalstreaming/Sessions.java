package com.naturalstreaming;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Sessions implements ApplicationRunner {
    private record Account(long id, String password) {}
    public record Principal(long id, String username) {}
    private record Session(Principal principal, boolean revoked, boolean expired) {}

    private final JdbcTemplate sql;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyPassword = passwords.encode("unknown-account");

    public Sessions(JdbcTemplate sql, Environment environment) {
        this.sql = sql;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!"admin".equals(environment.getProperty("ns.role"))) return;
        seed("admin", "CONSOLE_BOOTSTRAP_USER", "CONSOLE_BOOTSTRAP_PASSWORD");
        seed("app", "NS_TEST_USER", "NS_TEST_PASSWORD");
    }

    private void seed(String role, String userKey, String passwordKey) {
        String username = environment.getProperty(userKey);
        String password = environment.getProperty(passwordKey);
        if (username == null || password == null) return;
        sql.update("""
            INSERT INTO ns_account(username,password_hash,role,console_role_id) VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE id=id
            """, username, passwords.encode(password), role, "admin".equals(role) ? 1L : null);
    }

    @Transactional
    public String login(String role, Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        if (username == null || username.length() > 96 || password == null
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }
        var accounts = sql.query("""
            SELECT id,password_hash FROM ns_account WHERE username=? AND role=? AND enabled=TRUE FOR UPDATE
            """, (row, index) -> new Account(row.getLong(1), row.getString(2)), username, role);
        var account = accounts.isEmpty() ? null : accounts.getFirst();
        boolean matches = passwords.matches(password, account == null ? dummyPassword : account.password());
        if (!matches || account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_credentials");

        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        if ("app".equals(role)) {
            sql.update("UPDATE ns_session SET revoked=TRUE WHERE account_id=? AND revoked=FALSE", account.id());
        }
        long ttl = ("app".equals(role) ? Duration.ofDays(30) : Duration.ofHours(8)).toSeconds();
        sql.update("""
            INSERT INTO ns_session(token_hash,account_id,expires_at)
            VALUES (?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)))
            """, hash(token), account.id(), ttl);
        return token;
    }

    public Principal require(String role, String token) {
        return require(role, token, false);
    }

    private Principal require(String role, String token, boolean lockSession) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        var sessions = sql.query("""
            SELECT a.id,a.username,(s.revoked OR NOT a.enabled),s.expires_at<=UTC_TIMESTAMP(6)
            FROM ns_session s JOIN ns_account a ON a.id=s.account_id
            WHERE s.token_hash=? AND a.role=?
            """ + (lockSession ? " FOR UPDATE" : ""), (row, index) -> new Session(new Principal(row.getLong(1), row.getString(2)), row.getBoolean(3), row.getBoolean(4)), hash(token), role);
        if (sessions.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        var session = sessions.getFirst();
        if (session.revoked()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session_revoked");
        if (session.expired()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session_expired");
        return session.principal();
    }

    @Transactional
    public void changeConsolePassword(String token, Map<String, String> request) {
        String current = request.get("current_password");
        String next = request.get("new_password");
        ConsoleDirectory.validatePassword(next);
        if (current == null || current.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_current_password");
        }
        if (current.equals(next)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_new_password");
        }
        Principal principal = require("admin", token);
        String hash = sql.queryForObject("SELECT password_hash FROM ns_account WHERE id=? FOR UPDATE",
            String.class, principal.id());
        require("admin", token, true);
        if (!passwords.matches(current, hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_current_password");
        }
        sql.update("UPDATE ns_account SET password_hash=? WHERE id=?", passwords.encode(next), principal.id());
        sql.update("UPDATE ns_session SET revoked=TRUE WHERE account_id=? AND revoked=FALSE", principal.id());
    }

    public void logout(String role, String token) {
        require(role, token);
        sql.update("UPDATE ns_session SET revoked=TRUE WHERE token_hash=?", hash(token));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
