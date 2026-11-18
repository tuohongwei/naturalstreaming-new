package com.naturalstreaming;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class ThemeValidatorTest {
 @Test void rejectsTraversalAndAbsolutePaths(){
  for(String p:List.of("../secret", "/etc/passwd", "media/../../key", "media\\file", "https://example.org/a", "media//a"))
   assertFalse(ThemeValidator.safePath(p),p);
  assertTrue(ThemeValidator.safePath("media/1a.wav"));
 }
 @Test void rejectsInvalidGainAndTime(){
  assertFalse(ThemeValidator.gain(Double.NaN));assertFalse(ThemeValidator.gain(-1));
  assertFalse(ThemeValidator.gain(1.01));assertTrue(ThemeValidator.gain(0.08));
  assertFalse(ThemeValidator.overlap(2,2));assertTrue(ThemeValidator.overlap(1,2));
  assertFalse(ThemeValidator.overlap(1.5,2));
 }
 @Test void rejectsMislabelledContainersBeforeExternalProbe(){
  assertEquals("wav",MediaStore.detectContainer(new byte[]{82,73,70,70,0,0,0,0,87,65,86,69}));
  assertThrows(ApiError.class,()->MediaStore.detectContainer(new byte[]{82,73,70,70,0,0,0,0,65,86,73,32}));
  assertThrows(ApiError.class,()->MediaStore.detectContainer("#EXTM3U\nhttp://localhost/private".getBytes()));
 }
 @Test void distinguishesExpiredFromRevoked(){
  assertEquals("session_revoked",Auth.sessionError(2,3,"a","b",false,false));
  assertEquals("session_expired",Auth.sessionError(3,3,"b","b",true,false));
  assertEquals("session_revoked",Auth.sessionError(3,3,"b","b",false,true));
  assertNull(Auth.sessionError(3,3,"b","b",false,false));
 }
}
