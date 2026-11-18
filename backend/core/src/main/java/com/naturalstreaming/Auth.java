package com.naturalstreaming;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.security.*;import java.nio.charset.StandardCharsets;import java.util.*;
@Service public class Auth {
 final JdbcTemplate db;public Auth(JdbcTemplate d){db=d;}
 public record User(long id,String tokenHash,String deviceId){}
 public static String hash(String x){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 static String sessionError(long epoch,long current,String device,String active,boolean expired,boolean revoked){if(revoked||epoch!=current||!device.equals(active))return "session_revoked";return expired?"session_expired":null;}
 String bearer(HttpServletRequest r){String v=r.getHeader("Authorization");if(v==null||!v.startsWith("Bearer ")||v.length()>256||v.length()<20)throw ApiError.fail(401,"login_required");return v.substring(7);}
 public User user(HttpServletRequest r){return token(bearer(r));}
 User token(String token){String h=hash(token);var rows=db.queryForList("SELECT s.account_id,s.device_id,s.epoch,s.revoked,a.epoch current_epoch,a.active_device,a.enabled,(s.expires_at<=UTC_TIMESTAMP()) expired FROM login_session s JOIN account a ON a.id=s.account_id WHERE s.token_hash=?",h);
  if(rows.isEmpty())throw ApiError.fail(401,"login_required");var a=rows.getFirst();
  String error=sessionError(((Number)a.get("epoch")).longValue(),((Number)a.get("current_epoch")).longValue(),(String)a.get("device_id"),(String)a.get("active_device"),truth(a.get("expired")),truth(a.get("revoked"))||!truth(a.get("enabled")));
  if(error!=null)throw ApiError.fail(401,error);return new User(((Number)a.get("account_id")).longValue(),h,(String)a.get("device_id"));
 }
 static boolean truth(Object x){return Boolean.TRUE.equals(x)||(x instanceof Number n&&n.intValue()!=0);}
 public String service(HttpServletRequest r,String scope){String h=hash(bearer(r));var rows=db.queryForList("SELECT id,scopes FROM service_credential WHERE token_hash=? AND revoked=FALSE AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP()) FOR SHARE",h);if(rows.isEmpty())throw ApiError.fail(401,"service_auth_required");var s=rows.getFirst();if(!Set.copyOf(Arrays.asList(((String)s.get("scopes")).split(","))).contains(scope))throw ApiError.fail(403,"scope_required",scope);return (String)s.get("id");}
 public void entitled(User u,String theme){if(db.queryForObject("SELECT COUNT(*) FROM entitlement WHERE account_id=? AND theme_id=? AND allowed=TRUE",Integer.class,u.id(),theme)!=1)throw ApiError.fail(403,"theme_forbidden");}
}
