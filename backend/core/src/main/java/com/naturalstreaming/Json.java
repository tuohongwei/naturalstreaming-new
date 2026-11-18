package com.naturalstreaming;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
public final class Json {
 public static final JsonMapper M=JsonMapper.builder().build();
 public static String write(Object x){return M.writeValueAsString(x);}
 @SuppressWarnings("unchecked") public static Map<String,Object> read(String x){return M.readValue(x,Map.class);}
 @SuppressWarnings("unchecked") public static List<Map<String,Object>> objects(Object o){return (List<Map<String,Object>>)o;}
 public static String text(Map<String,?> m,String k){Object v=m.get(k);if(!(v instanceof String s))throw ApiError.fail(400,"invalid_request",k);return s;}
 public static double num(Object o){if(!(o instanceof Number n)||!Double.isFinite(n.doubleValue()))throw ApiError.fail(400,"invalid_number");return n.doubleValue();}
}
