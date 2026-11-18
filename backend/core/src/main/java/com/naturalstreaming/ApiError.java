package com.naturalstreaming;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
public class ApiError extends RuntimeException {
 final int status;final String code;final Object details;
 ApiError(int s,String c,Object d){super(c);status=s;code=c;details=d;}
 public static ApiError fail(int s,String c){return new ApiError(s,c,List.of());}
 public static ApiError fail(int s,String c,Object d){return new ApiError(s,c,d);}
 @RestControllerAdvice public static class Handler {
  @ExceptionHandler(ApiError.class) ResponseEntity<?> handle(ApiError e){return ResponseEntity.status(e.status).header("Cache-Control","no-store").body(Map.of("error",Map.of("code",e.code,"details",e.details)));}
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) ResponseEntity<?> missing(Exception e){return ResponseEntity.status(404).body(Map.of("error",Map.of("code","not_found","details",List.of())));}
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e){return ResponseEntity.status(405).body(Map.of("error",Map.of("code","method_not_allowed","details",List.of())));}
  @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MissingServletRequestParameterException.class}) ResponseEntity<?> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("error",Map.of("code","invalid_request","details",List.of())));}
  @ExceptionHandler(Exception.class) ResponseEntity<?> other(Exception e){System.err.println("Request failed: "+e.getClass().getSimpleName());return ResponseEntity.status(500).body(Map.of("error",Map.of("code","internal_error","details",List.of())));}
 }
}
