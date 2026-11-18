package com.naturalstreaming;
import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.annotation.Transactional;import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import jakarta.servlet.http.HttpServletRequest;import java.util.*;
@RestController @ConditionalOnProperty(name="ns.role",havingValue="api")
public class AccountApi {
 final Auth auth;final JdbcTemplate db;AccountApi(Auth a,JdbcTemplate d){auth=a;db=d;}
 @PostMapping("/v1/auth/login") @Transactional public Map<?,?> login(@RequestBody Map<String,String> b){String name=Json.text(b,"username"),pass=Json.text(b,"password"),device=Json.text(b,"deviceId");if(!device.matches("[A-Za-z0-9_-]{8,100}")||name.length()>80||pass.length()>72)throw ApiError.fail(400,"invalid_request");
  var rows=db.queryForList("SELECT * FROM account WHERE username=? FOR UPDATE",name);if(rows.isEmpty()||!Auth.truth(rows.getFirst().get("enabled"))||!new BCryptPasswordEncoder().matches(pass,(String)rows.getFirst().get("password_hash")))throw ApiError.fail(401,"invalid_login");var a=rows.getFirst();long id=((Number)a.get("id")).longValue(),epoch=((Number)a.get("epoch")).longValue()+1;
  db.update("UPDATE account SET active_device=?,epoch=? WHERE id=?",device,epoch,id);db.update("UPDATE login_session SET revoked=TRUE WHERE account_id=?",id);String token=Auth.random();db.update("INSERT INTO login_session(token_hash,account_id,device_id,epoch,expires_at) VALUES(?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 DAY))",Auth.hash(token),id,device,epoch);
  return Map.of("token",token,"expiresIn",2592000,"accountId",id,"deviceId",device,"checkIntervalSeconds",60);
 }
 @GetMapping("/v1/me") public Map<?,?> me(HttpServletRequest r){var u=auth.user(r);return Map.of("account",db.queryForMap("SELECT id,username FROM account WHERE id=?",u.id()),"deviceId",u.deviceId(),"entitlements",db.queryForList("SELECT theme_id,allowed FROM entitlement WHERE account_id=? ORDER BY theme_id",u.id()),"checkIntervalSeconds",60,"offlineExpiryEnforced",false);}
 @PostMapping("/v1/auth/logout") public Map<?,?> logout(HttpServletRequest r){var u=auth.user(r);db.update("UPDATE login_session SET revoked=TRUE WHERE token_hash=?",u.tokenHash());return Map.of("status","logged_out");}
}
