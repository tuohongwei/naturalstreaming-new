package com.naturalstreaming;
import org.springframework.stereotype.Service;import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;import java.io.*;import java.util.*;import java.util.concurrent.TimeUnit;
@Service public class MediaStore {
 final Path root;final String probe,ffmpeg;
 MediaStore(@Value("${ns.storage}")String r,@Value("${ns.ffprobe}")String p,@Value("${ns.ffmpeg}")String f){root=Path.of(r).toAbsolutePath().normalize();probe=p;ffmpeg=f;}
 Path path(String hash){if(!hash.matches("[a-f0-9]{64}"))throw ApiError.fail(400,"invalid_hash");Path p=root.resolve(hash);if(Files.isSymbolicLink(p))throw ApiError.fail(500,"invalid_storage");return p;}
 public Path temporary()throws IOException{Files.createDirectories(root);return Files.createTempFile(root,"upload-",".part");}
 public void install(Path tmp,String hash)throws IOException{Path dest=path(hash);try{Files.move(tmp,dest,StandardCopyOption.ATOMIC_MOVE);}catch(FileAlreadyExistsException e){Files.deleteIfExists(tmp);}}
 String command(List<String> args,Path input)throws Exception{Path report=Files.createTempFile(root,"probe-",".txt");try{List<String> cmd=new ArrayList<>(args);Process p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(report.toFile()).start();if(!p.waitFor(90,TimeUnit.SECONDS)){p.destroyForcibly();throw ApiError.fail(422,"media_probe_timeout");}if(p.exitValue()!=0)throw ApiError.fail(422,"media_decode_failed");if(Files.size(report)>1000000)throw ApiError.fail(422,"media_probe_failed");return Files.readString(report);}finally{Files.deleteIfExists(report);}}

 public static String detectContainer(byte[] h){
  if(h.length>=12 && h[0]=='R'&&h[1]=='I'&&h[2]=='F'&&h[3]=='F'&&h[8]=='W'&&h[9]=='A'&&h[10]=='V'&&h[11]=='E')return "wav";
  if(h.length>=12 && h[4]=='f'&&h[5]=='t'&&h[6]=='y'&&h[7]=='p')return "mov";
  if(h.length>=3 && (h[0]&255)==255&&(h[1]&255)==216&&(h[2]&255)==255)return "jpeg_pipe";
  byte[] png={(byte)137,80,78,71,13,10,26,10};
  if(h.length>=8&&Arrays.equals(Arrays.copyOf(h,8),png))return "png_pipe";
  throw ApiError.fail(422,"unsupported_container");
 }
 List<String> inputArgs(String executable,String format,Path p){return new ArrayList<>(List.of(executable,"-v","error","-protocol_whitelist","file,pipe","-format_whitelist","wav,mov,jpeg_pipe,png_pipe","-f",format,"-i",p.toString()));}
 public Map<String,Object> inspect(Path p)throws Exception{
  byte[] header;try(var in=Files.newInputStream(p)){header=in.readNBytes(16);}
  String format=detectContainer(header);
  List<String> args=inputArgs(probe,format,p);args.addAll(List.of("-count_frames","-show_streams","-show_format","-of","json"));
  var result=Json.read(command(args,p));var streams=Json.objects(result.get("streams"));
  if(streams==null||streams.size()!=1)throw ApiError.fail(422,"unsupported_streams");
  var stream=streams.getFirst();String codec=Json.text(stream,"codec_name"),type=Json.text(stream,"codec_type");
  if("audio".equals(type)){
   String container,normalized;int channels=((Number)stream.get("channels")).intValue();
   if(channels<1||channels>2)throw ApiError.fail(422,"unsupported_channels");
   if("mov".equals(format)&&"aac".equals(codec)&&"LC".equals(stream.get("profile"))){container="m4a";normalized="aac-lc";}
   else if("wav".equals(format)&&"pcm_s16le".equals(codec)){container="wav";normalized=codec;}
   else throw ApiError.fail(422,"unsupported_codec");
   int rate=Integer.parseInt(stream.get("sample_rate").toString());
   if(rate<8000||rate>192000)throw ApiError.fail(422,"unsupported_sample_rate");
   double declared=Double.parseDouble(((Map<?,?>)result.get("format")).get("duration").toString());
   if(!Double.isFinite(declared)||declared<=0||declared>7200)throw ApiError.fail(422,"invalid_duration");
   args=inputArgs(ffmpeg,format,p);args.addAll(List.of("-xerror","-map","0:a:0","-af","asetpts=N/SR/TB","-progress","pipe:1","-nostats","-f","null","-"));
   String decoded=command(args,p);var matcher=java.util.regex.Pattern.compile("(?m)^out_time_us=(-?[0-9]+)").matcher(decoded);long micros=-1;
   while(matcher.find())micros=Long.parseLong(matcher.group(1));double duration=micros/1000000.0;
   if(duration<=0||duration>7200||Math.abs(declared-duration)>0.15)throw ApiError.fail(422,"duration_mismatch");
   return Map.of("kind","audio","codec",normalized,"container",container,"channels",channels,"sampleRate",rate,"duration",duration,"mime",container.equals("wav")?"audio/wav":"audio/mp4");
  }
  if("video".equals(type)&&Set.of("jpeg_pipe","png_pipe").contains(format)&&Set.of("mjpeg","png").contains(codec)){
   int w=((Number)stream.get("width")).intValue(),h=((Number)stream.get("height")).intValue();
   if(w<=0||h<=0||w>8192||h>8192||!"1".equals(String.valueOf(stream.get("nb_read_frames"))))throw ApiError.fail(422,"invalid_image");
   args=inputArgs(ffmpeg,format,p);args.addAll(List.of("-xerror","-f","null","-"));command(args,p);
   return Map.of("kind","image","codec",codec,"container",format.equals("png_pipe")?"png":"jpg","channels",0,"sampleRate",0,"duration",0,"mime",format.equals("png_pipe")?"image/png":"image/jpeg");
  }
  throw ApiError.fail(422,"unsupported_media");
 }
}
