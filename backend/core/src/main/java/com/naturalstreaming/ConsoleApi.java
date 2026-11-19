package com.naturalstreaming;

import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.servlet.http.*;
import java.io.*;import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;import java.time.Duration;import java.util.*;

/** Browser sessions never receive the scoped upstream service credential. */
@RestController @ConditionalOnProperty(name="ns.role",havingValue="admin")
public class ConsoleApi {
 final JdbcTemplate db;final String serviceToken,origin,bootstrapUser,bootstrapPassword;
 final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
 final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 ConsoleApi(JdbcTemplate d,@Value("${CONSOLE_SERVICE_TOKEN:}")String token,@Value("${CONSOLE_ORIGIN:https://admin.naturalstreaming.net}")String o,@Value("${CONSOLE_BOOTSTRAP_USER:}")String u,@Value("${CONSOLE_BOOTSTRAP_PASSWORD:}")String p){db=d;serviceToken=token;origin=o;bootstrapUser=u;bootstrapPassword=p;}
 @EventListener(ApplicationReadyEvent.class) public void bootstrap(){
  if(!bootstrapUser.isBlank()&&!bootstrapPassword.isBlank()){
   if(!bootstrapUser.matches("[A-Za-z0-9_-]{3,80}")||!passwordValid(bootstrapPassword))throw new IllegalArgumentException("Invalid console bootstrap identity");
   db.update("INSERT IGNORE INTO console_user(username,password_hash) VALUES(?,?)",bootstrapUser,passwords.encode(bootstrapPassword));
  }
 }
 static boolean passwordValid(String p){return p!=null&&p.length()>=12&&p.getBytes(StandardCharsets.UTF_8).length<=72;}
 static boolean originAllowed(String actual,String expected){return expected.equals(actual);}
 static boolean csrfMatches(String actual,String expected){return actual!=null&&expected!=null&&MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8));}
 void checkOrigin(HttpServletRequest r){if(!originAllowed(r.getHeader("Origin"),origin))throw ApiError.fail(403,"console_origin_required");}
 Map<String,Object> sessionUser(HttpServletRequest r){HttpSession s=r.getSession(false);if(s==null||!(s.getAttribute("uid") instanceof Long id))throw ApiError.fail(401,"console_login_required");
  var rows=db.queryForList("SELECT id,username,epoch,enabled FROM console_user WHERE id=?",id);
  if(rows.isEmpty()||!Auth.truth(rows.getFirst().get("enabled"))||!Objects.equals(((Number)rows.getFirst().get("epoch")).longValue(),s.getAttribute("epoch"))){s.invalidate();throw ApiError.fail(401,"console_session_revoked");}return rows.getFirst();
 }
 void protect(HttpServletRequest r){checkOrigin(r);HttpSession s=r.getSession(false);if(s==null||!csrfMatches(r.getHeader("X-CSRF-Token"),(String)s.getAttribute("csrf")))throw ApiError.fail(403,"console_csrf_required");}
 Map<?,?> sessionBody(HttpSession s,Map<String,Object> u){return Map.of("username",u.get("username"),"csrfToken",s.getAttribute("csrf"),"expiresIn",1800);}
 @PostMapping("/console/login") public Map<?,?> login(HttpServletRequest r,@RequestBody Map<String,String>b){checkOrigin(r);String username=Json.text(b,"username"),password=Json.text(b,"password");if(username.length()>80||password.getBytes(StandardCharsets.UTF_8).length>72)throw ApiError.fail(400,"invalid_request");
  var rows=db.queryForList("SELECT id,username,password_hash,epoch,enabled FROM console_user WHERE username=?",username);
  if(rows.isEmpty()||!Auth.truth(rows.getFirst().get("enabled"))||!passwords.matches(password,(String)rows.getFirst().get("password_hash")))throw ApiError.fail(401,"console_invalid_login");
  if(r.getSession(false)!=null)r.getSession(false).invalidate();var s=r.getSession(true);var u=rows.getFirst();s.setAttribute("uid",((Number)u.get("id")).longValue());s.setAttribute("epoch",((Number)u.get("epoch")).longValue());s.setAttribute("csrf",Auth.random());s.setMaxInactiveInterval(1800);
  db.update("INSERT INTO audit_log(actor,action,target) VALUES(?,?,?)","console:"+username,"console_login","console");return sessionBody(s,u);
 }
 @GetMapping("/console/session") public Map<?,?> session(HttpServletRequest r){return sessionBody(r.getSession(false),sessionUser(r));}
 @PostMapping("/console/logout") public Map<?,?> logout(HttpServletRequest r){sessionUser(r);protect(r);r.getSession(false).invalidate();return Map.of("status","logged_out");}
 @PostMapping("/console/password") public Map<?,?> password(HttpServletRequest r,@RequestBody Map<String,String>b){var u=sessionUser(r);protect(r);String old=Json.text(b,"oldPassword"),next=Json.text(b,"newPassword");if(!passwordValid(next)||old.getBytes(StandardCharsets.UTF_8).length>72)throw ApiError.fail(400,"invalid_password");
  String hash=db.queryForObject("SELECT password_hash FROM console_user WHERE id=?",String.class,u.get("id"));if(!passwords.matches(old,hash))throw ApiError.fail(403,"wrong_password");
  if(db.update("UPDATE console_user SET password_hash=?,epoch=epoch+1 WHERE id=? AND epoch=?",passwords.encode(next),u.get("id"),u.get("epoch"))!=1)throw ApiError.fail(409,"console_session_changed");
  r.getSession(false).invalidate();return Map.of("status","password_changed_login_again");
 }
 static String target(String path){
  if(path==null||!path.matches("[A-Za-z0-9_./-]+")||path.contains("..")||path.contains("//"))throw ApiError.fail(400,"invalid_console_path");
  if(path.equals("admin/themes")||path.startsWith("admin/themes/")||path.equals("admin/accounts")||path.startsWith("admin/accounts/")||path.equals("admin/audit"))return "http://127.0.0.1:8191/v1/"+path;
  if(path.matches("uploads/[a-f0-9]{64}(?:/content)?")||path.matches("exports/[A-Za-z0-9_-]{1,80}/[A-Za-z0-9_-]{1,80}"))return "http://127.0.0.1:8192/v1/"+path;
  throw ApiError.fail(403,"console_route_forbidden");
 }
 @RequestMapping(value="/console/api/**",method={RequestMethod.GET,RequestMethod.HEAD,RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE})
 public void proxy(HttpServletRequest r,HttpServletResponse out)throws Exception{
  sessionUser(r);if(!Set.of("GET","HEAD").contains(r.getMethod()))protect(r);
  if(serviceToken.length()<32)throw ApiError.fail(503,"console_not_configured");
  String path=r.getRequestURI().substring("/console/api/".length());String destination=target(path);boolean body=Set.of("POST","PUT","DELETE").contains(r.getMethod());long limit=path.startsWith("uploads/")?134217728:1048576;
  if(r.getContentLengthLong()>limit)throw ApiError.fail(413,"request_too_large");
  var builder=HttpRequest.newBuilder(URI.create(destination)).timeout(Duration.ofSeconds(180)).header("Authorization","Bearer "+serviceToken).header("User-Agent","NaturalStreaming/1.0 (Console gateway)");
  String contentType=r.getContentType();if(contentType!=null)builder.header("Content-Type",contentType);
  for(String h:List.of("Range","If-Range"))if(r.getHeader(h)!=null)builder.header(h,r.getHeader(h));
  InputStream input=body?new BoundedInput(r.getInputStream(),limit):InputStream.nullInputStream();
  boolean revisionAction=path.matches("admin/themes/[A-Za-z0-9_-]+/versions/[A-Za-z0-9_-]+/(?:publish|validate)")&&r.getMethod().equals("POST");
  if(revisionAction){byte[] json=input.readAllBytes();var expected=Json.read(new String(json,StandardCharsets.UTF_8));if(!(expected.get("revision") instanceof Number n)||n.longValue()<1||n.doubleValue()!=n.longValue())throw ApiError.fail(400,"revision_required");builder.method(r.getMethod(),HttpRequest.BodyPublishers.ofByteArray(json));}
  else builder.method(r.getMethod(),body?HttpRequest.BodyPublishers.ofInputStream(()->input):HttpRequest.BodyPublishers.noBody());
  var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());out.setStatus(response.statusCode());
  for(String h:List.of("Content-Type","Content-Length","Content-Range","Accept-Ranges","ETag","Content-Disposition","X-Content-Type-Options"))response.headers().firstValue(h).ifPresent(v->out.setHeader(h,v));
  try(var responseBody=response.body()){if(!r.getMethod().equals("HEAD"))responseBody.transferTo(out.getOutputStream());}
 }
 static class BoundedInput extends FilterInputStream {long remaining;BoundedInput(InputStream i,long n){super(i);remaining=n;}
  @Override public int read()throws IOException{int n=in.read();if(n!=-1&&--remaining<0)throw new IOException("request_too_large");return n;}
  @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,(int)Math.min(len,remaining+1));if(n>0&&(remaining-=n)<0)throw new IOException("request_too_large");return n;}
 }
}
