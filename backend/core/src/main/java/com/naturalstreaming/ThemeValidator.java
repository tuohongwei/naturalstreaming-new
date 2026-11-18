package com.naturalstreaming;
import org.springframework.stereotype.Service;import org.springframework.jdbc.core.JdbcTemplate;import java.util.*;
@Service public class ThemeValidator {
 final JdbcTemplate db;ThemeValidator(JdbcTemplate d){db=d;}
 public static boolean safePath(String p){return p!=null&&p.length()<=180&&p.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)+")&&Arrays.stream(p.split("/",-1)).noneMatch(s->s.equals(".")||s.equals(".."));}
 public static boolean gain(double g){return Double.isFinite(g)&&g>=0&&g<=1;}
 public static boolean overlap(double seconds,double duration){return Double.isFinite(seconds)&&Double.isFinite(duration)&&seconds>=0&&duration>0&&seconds<duration&&seconds*2<=duration;}
 static boolean id(String v){return v!=null&&v.matches("[A-Za-z0-9_-]{1,80}");}
 static void require(boolean b,String field){if(!b)throw ApiError.fail(422,"validation_failed",List.of(Map.of("field",field,"code","invalid_or_missing")));}
 public void validate(String theme,Map<String,Object> draft){
  try { validateBody(theme,draft); }catch(ApiError e){throw e;}catch(RuntimeException e){throw ApiError.fail(422,"validation_failed",List.of(Map.of("field","scene_or_manifest","code","invalid_structure")));}
 }
 void validateBody(String theme,Map<String,Object> draft){
  var scene=(Map<String,Object>)draft.get("scene");var manifest=(Map<String,Object>)draft.get("manifest");var license=(Map<String,Object>)draft.get("license");
  require(scene!=null&&manifest!=null,"scene,manifest");require(theme.equals(scene.get("id"))&&theme.equals(manifest.get("theme_id")),"id");
  require(Json.num(scene.get("schema_version"))==1&&Json.num(manifest.get("schema_version"))==1,"schema_version");
  require(nonempty(scene.get("name"),120),"name");require(license!=null&&Boolean.TRUE.equals(license.get("reviewed"))&&nonempty(license.get("source"),2000)&&nonempty(license.get("license"),2000),"license");
  var files=Json.objects(manifest.get("files"));require(files!=null&&!files.isEmpty()&&files.size()<=129,"manifest.files");Map<String,Map<String,Object>> actual=new HashMap<>();
  for(var f:files){String path=Json.text(f,"path"),sha=Json.text(f,"sha256");require(safePath(path)&&!actual.containsKey(path),"manifest.files.path:"+path);require(sha.matches("[a-f0-9]{64}"),"sha256:"+path);
   var rows=db.queryForList("SELECT * FROM media_file WHERE sha256=?",sha);require(!rows.isEmpty(),"missing_file:"+path);var blob=rows.getFirst();require(Json.num(f.get("bytes"))==((Number)blob.get("bytes")).doubleValue(),"bytes:"+path);
   if(f.containsKey("channels"))require(Json.num(f.get("channels"))==((Number)blob.get("channels")).doubleValue(),"channels:"+path);
   if(f.containsKey("codec"))require(f.get("codec").equals(blob.get("codec")),"codec:"+path);
   if(f.containsKey("container"))require(f.get("container").equals(blob.get("container")),"container:"+path);
   for(String k:List.of("sample_rate","sampleRate"))if(f.containsKey(k))require(Json.num(f.get(k))==((Number)blob.get("sample_rate")).doubleValue(),k+":"+path);
   for(String k:List.of("duration","duration_seconds"))if(f.containsKey(k))require(Math.abs(Json.num(f.get(k))-((Number)blob.get("duration")).doubleValue())<=0.15,k+":"+path);
   actual.put(path,blob);
  }
  if(scene.containsKey("cover"))require(actual.containsKey(scene.get("cover"))&&"image".equals(actual.get(scene.get("cover")).get("kind")),"cover");
  var tracks=Json.objects(scene.get("tracks"));require(tracks!=null&&!tracks.isEmpty()&&tracks.size()<=32,"tracks");Set<String> ids=new HashSet<>();
  for(var t:tracks){String tid=Json.text(t,"id");require(id(tid)&&ids.add(tid),"tracks.id:"+tid);require(nonempty(t.get("label"),120)&&!Json.text(t,"label").matches(".*轨道\\s*0?\\d+.*"),"tracks.label:"+tid);require(nonempty(t.get("description"),1000),"tracks.description:"+tid);require(gain(Json.num(t.get("gain"))),"tracks.gain:"+tid);
   var clips=(List<String>)t.get("clips");require(clips!=null&&clips.size()==2,"tracks.clips:"+tid);
   for(String p:clips){var f=actual.get(p);require(f!=null&&"audio".equals(f.get("kind")),"tracks.clip:"+tid+":"+p);require(overlap(Json.num(t.get("overlap_seconds")),((Number)f.get("duration")).doubleValue()),"tracks.overlap_seconds:"+tid);require(Json.num(t.get("channels"))==((Number)f.get("channels")).doubleValue(),"tracks.channels:"+tid);}
   boolean point="point_source".equals(t.get("spatial_role")),bed="stereo_bed".equals(t.get("spatial_role"));require(point||bed,"spatial_role:"+tid);
   require(point?(Json.num(t.get("channels"))==1&&Boolean.FALSE.equals(t.get("diffuse"))):(Json.num(t.get("channels"))==2&&Boolean.TRUE.equals(t.get("diffuse"))),"spatial_role_channels:"+tid);
   if(point){var pos=(List<?>)t.get("map_position");require(pos!=null&&pos.size()==2,"map_position:"+tid);Json.num(pos.get(0));Json.num(pos.get(1));}
  }
  Set<String> presets=new HashSet<>();var ps=Json.objects(scene.get("presets"));require(ps!=null&&!ps.isEmpty()&&ps.size()<=64,"presets");
  for(var p:ps){String pid=Json.text(p,"id");require(id(pid)&&presets.add(pid),"presets.id");var gains=(Map<String,Object>)p.get("gains");require(gains!=null&&gains.keySet().equals(ids),"presets.gains:"+pid);for(var g:gains.values())require(gain(Json.num(g)),"presets.gain:"+pid);require(Json.num(p.get("transition_seconds"))==2,"presets.transition_seconds:"+pid);
   if(p.get("animation") instanceof Map<?,?> a&&Boolean.TRUE.equals(a.get("enabled"))){require("soft".equals(a.get("mode")),"animation.mode");require(Json.num(a.get("interval_seconds"))>=2&&Json.num(a.get("interval_seconds"))<=3600,"animation.interval");double lo=Json.num(a.get("min_gain_multiplier")),hi=Json.num(a.get("max_gain_multiplier"));require(lo>=0&&hi>=lo&&hi<=2,"animation.multiplier");}
  }
  require(presets.contains(scene.get("default_preset")),"default_preset");
 }
 static boolean nonempty(Object o,int max){return o instanceof String s&&!s.isBlank()&&s.length()<=max;}
}
