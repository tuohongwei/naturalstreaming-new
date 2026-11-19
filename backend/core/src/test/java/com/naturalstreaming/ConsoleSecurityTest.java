package com.naturalstreaming;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ConsoleSecurityTest {
 @Test void onlyAllowsFixedLocalProxyRoutes(){
  assertEquals("http://127.0.0.1:8191/v1/admin/themes",ConsoleApi.target("admin/themes"));
  assertEquals("http://127.0.0.1:8192/v1/uploads/"+"a".repeat(64),ConsoleApi.target("uploads/"+"a".repeat(64)));
  for(String s:new String[]{"http://other/","admin/../auth/login","admin/%2e%2e/auth","uploads//x","admin/credentials","admin/audit?x=1","exports/../../etc"})assertThrows(ApiError.class,()->ConsoleApi.target(s));
 }
 @Test void originAndCsrfAreExplicit(){
  assertTrue(ConsoleApi.originAllowed("https://admin.naturalstreaming.net","https://admin.naturalstreaming.net"));
  assertFalse(ConsoleApi.originAllowed(null,"https://admin.naturalstreaming.net"));
  assertFalse(ConsoleApi.originAllowed("https://evil.example","https://admin.naturalstreaming.net"));
  assertFalse(ConsoleApi.csrfMatches(null,"known"));assertFalse(ConsoleApi.csrfMatches("bad","known"));assertTrue(ConsoleApi.csrfMatches("known","known"));
 }
}
