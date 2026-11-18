package com.naturalstreaming;
import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import java.util.UUID;
@Component public class ResponsePolicy extends OncePerRequestFilter {
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Request-Id",UUID.randomUUID().toString());chain.doFilter(request,response);
 }
}
