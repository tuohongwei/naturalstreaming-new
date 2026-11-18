package com.naturalstreaming;
import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;import java.util.*;
@RestController @ConditionalOnProperty(name="ns.role",havingValue="api") public class ThemeApi {
 final Auth auth;final Catalog catalog;final String media;ThemeApi(Auth a,Catalog c,@Value("${ns.media-url}")String m){auth=a;catalog=c;media=m;}
 @GetMapping("/v1/catalog/sync") Map<?,?> sync(HttpServletRequest r,@RequestParam(required=false)String cursor){return catalog.sync(auth.user(r),cursor);}
 @GetMapping("/v1/themes") Map<?,?> themes(HttpServletRequest r){return catalog.sync(auth.user(r),null);}
 @GetMapping("/v1/themes/{id}/versions/{version}") Map<?,?> version(HttpServletRequest r,@PathVariable String id,@PathVariable String version){var u=auth.user(r);auth.entitled(u,id);return catalog.document(catalog.version(id,version,true));}
 @PostMapping("/v1/media/download-urls") Map<?,?> urls(HttpServletRequest r,@RequestBody Map<String,Object>b){var u=auth.user(r);String theme=Json.text(b,"themeId"),version=Json.text(b,"version");auth.entitled(u,theme);var v=catalog.version(theme,version,true);var files=Json.objects(Json.read((String)v.get("manifest_json")).get("files"));var paths=(List<String>)b.get("paths");if(paths==null||paths.isEmpty()||paths.size()>129)throw ApiError.fail(400,"invalid_request");long expires=System.currentTimeMillis()/1000+300;List<Object> out=new ArrayList<>();
  for(String path:paths){var f=files.stream().filter(x->path.equals(x.get("path"))).findFirst().orElseThrow(()->ApiError.fail(404,"file_missing",path));String ticket=catalog.seal(Json.write(Map.of("account",u.id(),"tokenHash",u.tokenHash(),"theme",theme,"version",version,"path",path,"sha256",f.get("sha256"),"expires",expires)));out.add(Map.of("path",path,"sha256",f.get("sha256"),"bytes",f.get("bytes"),"url",media+"/v1/media/"+f.get("sha256")+"?ticket="+ticket,"expiresAt",expires,"requiredHeaders",List.of("Authorization")));}
  return Map.of("files",out);
 }
}
