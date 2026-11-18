package com.naturalstreaming;
import org.springframework.web.bind.annotation.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.beans.factory.annotation.Value;import java.util.*;
@RestController public class HealthApi {
 final JdbcTemplate db;final String role;HealthApi(JdbcTemplate d,@Value("${ns.role}")String r){db=d;role=r;}
 @GetMapping("/healthz") Map<?,?> health(){db.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP","service","naturalstreaming-"+role,"version","1.0.0");}
}
