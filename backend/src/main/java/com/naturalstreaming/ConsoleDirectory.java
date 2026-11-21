package com.naturalstreaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConsoleDirectory {
    private static final long SUPER_ADMIN = 1;
    private static final Set<String> PERMISSIONS = Set.of("assets.read", "assets.write", "themes.read", "themes.write",
            "presets.read", "presets.write", "presets.publish", "accounts.manage", "roles.manage", "members.manage");

    private record Role(long id, String name, Set<String> permissions, boolean builtin) {
        Map<String, Object> json() {
            return Map.of("id", id, "name", name, "permissions", permissions.stream().sorted().toList(), "builtin", builtin);
        }
    }

    private record Account(long id, String username, boolean enabled, Long roleId) {
        Map<String, Object> json() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("username", username);
            result.put("enabled", enabled);
            if (roleId != null) result.put("role_id", roleId);
            return result;
        }
    }

    private final JdbcTemplate sql;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final ObjectMapper json = new ObjectMapper();

    public ConsoleDirectory(JdbcTemplate sql) {
        this.sql = sql;
    }

    public List<Map<String, Object>> roles() {
        return sql.query("SELECT id,name,permissions,builtin FROM ns_console_role ORDER BY id", this::readRole)
                .stream().map(Role::json).toList();
    }

    @Transactional
    public Map<String, Object> createRole(Map<String, ?> request, Set<String> actorPermissions) {
        String name = roleName(request);
        Set<String> grants = requestedPermissions(request);
        requireGrant(grants, actorPermissions);
        try {
            sql.update("INSERT INTO ns_console_role(name,permissions) VALUES (?,?)", name, serialize(grants));
        } catch (DuplicateKeyException error) {
            throw conflict("role_name_exists");
        }
        return sql.query("SELECT id,name,permissions,builtin FROM ns_console_role WHERE name=?", this::readRole, name)
                .getFirst().json();
    }

    @Transactional
    public Map<String, Object> updateRole(long id, Map<String, ?> request, Set<String> actorPermissions) {
        String name = roleName(request);
        Set<String> grants = requestedPermissions(request);
        Role current = lockRole(id);
        if (current.builtin()) throw conflict("builtin_role_immutable");
        requireGrant(current.permissions(), actorPermissions);
        requireGrant(grants, actorPermissions);
        try {
            sql.update("UPDATE ns_console_role SET name=?,permissions=? WHERE id=?", name, serialize(grants), id);
        } catch (DuplicateKeyException error) {
            throw conflict("role_name_exists");
        }
        // Permissions are read from the current role on every request.
        return new Role(id, name, grants, false).json();
    }

    public Set<String> permissions(long accountId) {
        return sql.query("""
            SELECT r.permissions FROM ns_console_role r JOIN ns_account a ON a.console_role_id=r.id
            WHERE a.id=? AND a.role='admin' AND a.enabled=TRUE
            """, (row, index) -> parse(row.getString(1)), accountId).stream().findFirst().orElseGet(Set::of);
    }

    public List<Map<String, Object>> accounts(String kind) {
        return sql.query("SELECT id,username,enabled,console_role_id FROM ns_account WHERE role=? ORDER BY id",
                ConsoleDirectory::readAccount, kind).stream().map(Account::json).toList();
    }

    @Transactional
    public Map<String, Object> createAccount(String kind, Map<String, ?> request, Set<String> actorPermissions) {
        String username = text(request, "username").strip();
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) throw invalid("invalid_username");
        String password = text(request, "password");
        validatePassword(password);
        Long roleId = "admin".equals(kind) ? roleId(request) : null;
        if (roleId != null) requireGrant(lockRole(roleId).permissions(), actorPermissions);
        try {
            sql.update("INSERT INTO ns_account(username,password_hash,role,console_role_id) VALUES (?,?,?,?)",
                    username, encoder.encode(password), kind, roleId);
        } catch (DuplicateKeyException error) {
            throw conflict("username_exists");
        }
        return sql.query("SELECT id,username,enabled,console_role_id FROM ns_account WHERE username=? AND role=?",
                ConsoleDirectory::readAccount, username, kind).getFirst().json();
    }

    @Transactional
    public Map<String, Object> updateAccount(String kind, long id, Map<String, ?> request, Set<String> actorPermissions) {
        boolean admin = "admin".equals(kind);
        boolean enabled = enabled(request);
        Long nextRole = admin ? roleId(request) : null;
        // Serialize admin changes even when different administrators would be disabled concurrently.
        if (admin) lockRole(SUPER_ADMIN);
        Account current = lockAccount(kind, id);
        if (admin) {
            if (current.roleId() != null) requireGrant(lockRole(current.roleId()).permissions(), actorPermissions);
            requireGrant(lockRole(nextRole).permissions(), actorPermissions);
            if (current.enabled() && Long.valueOf(SUPER_ADMIN).equals(current.roleId())
                    && (!enabled || nextRole != SUPER_ADMIN) && !hasOtherSuperAdmin(id)) {
                throw conflict("last_enabled_super_admin");
            }
        }
        sql.update("UPDATE ns_account SET enabled=?,console_role_id=? WHERE id=?", enabled, nextRole, id);
        if (!enabled || !java.util.Objects.equals(current.roleId(), nextRole)) revokeSessions(id);
        return new Account(id, current.username(), enabled, nextRole).json();
    }

    @Transactional
    public void resetMemberPassword(long id, Map<String, ?> request) {
        String password = text(request, "password");
        validatePassword(password);
        lockAccount("app", id);
        sql.update("UPDATE ns_account SET password_hash=? WHERE id=?", encoder.encode(password), id);
        revokeSessions(id);
    }

    static void validatePassword(String password) {
        int length = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (length < 9 || length > 72 || password.isBlank()) throw invalid("invalid_password_length");
    }

    private boolean hasOtherSuperAdmin(long id) {
        return !sql.queryForList("""
            SELECT id FROM ns_account WHERE role='admin' AND enabled=TRUE AND console_role_id=? AND id<>?
            FOR UPDATE
            """, Long.class, SUPER_ADMIN, id).isEmpty();
    }

    void lockAdministration() {
        lockRole(SUPER_ADMIN);
    }

    private Role lockRole(long id) {
        return sql.query("SELECT id,name,permissions,builtin FROM ns_console_role WHERE id=? FOR UPDATE", this::readRole, id)
                .stream().findFirst().orElseThrow(() -> notFound("role_not_found"));
    }

    private Account lockAccount(String kind, long id) {
        return sql.query("SELECT id,username,enabled,console_role_id FROM ns_account WHERE id=? AND role=? FOR UPDATE",
                ConsoleDirectory::readAccount, id, kind).stream().findFirst().orElseThrow(() -> notFound("account_not_found"));
    }

    private Role readRole(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Role(row.getLong(1), row.getString(2), parse(row.getString(3)), row.getBoolean(4));
    }

    private static Account readAccount(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Account(row.getLong(1), row.getString(2), row.getBoolean(3), row.getObject(4, Long.class));
    }

    private void revokeSessions(long accountId) {
        sql.update("UPDATE ns_session SET revoked=TRUE WHERE account_id=? AND revoked=FALSE", accountId);
    }

    private Set<String> parse(String value) {
        try {
            return Set.copyOf(json.readValue(value, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid role permissions in database", error);
        }
    }

    private String serialize(Set<String> value) {
        try {
            return json.writeValueAsString(new ArrayList<>(value));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not encode role permissions", error);
        }
    }

    private static void requireGrant(Set<String> grants, Set<String> actorPermissions) {
        if (!actorPermissions.containsAll(grants)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "permission_grant_exceeds_actor");
        }
    }

    private static Set<String> requestedPermissions(Map<String, ?> request) {
        if (!(request.get("permissions") instanceof Collection<?> items)) throw invalid("invalid_permissions");
        Set<String> grants = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String permission) || !PERMISSIONS.contains(permission)) throw invalid("invalid_permissions");
            grants.add(permission);
        }
        return grants;
    }

    private static String roleName(Map<String, ?> request) {
        String name = text(request, "name").strip();
        if (name.isEmpty() || name.length() > 96) throw invalid("invalid_role_name");
        return name;
    }

    private static long roleId(Map<String, ?> request) {
        if (!(request.get("role_id") instanceof Number number) || number.longValue() < 1
                || !Long.toString(number.longValue()).equals(number.toString())) throw invalid("invalid_role_id");
        return number.longValue();
    }

    private static boolean enabled(Map<String, ?> request) {
        if (!(request.get("enabled") instanceof Boolean value)) throw invalid("invalid_enabled");
        return value;
    }

    private static String text(Map<String, ?> request, String key) {
        if (!(request.get(key) instanceof String value)) throw invalid("invalid_" + key);
        return value;
    }

    private static ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private static ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }
}
