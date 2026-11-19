package dev.raj.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class ApiController {
 private final McpClientService mcp;
 private final RestClient ollama;
 private final String model;
 private final String ollamaUrl;
 private final Map<String,String> approvals=new ConcurrentHashMap<>();
 private static final Set<String> COMMANDS=Set.of("pwd","date","whoami","uname",
   "java -version","git status","git log -1 --oneline","ls -la");

 public ApiController(McpClientService mcp,@Value("${app.ollama-url}") String url,
                      @Value("${app.ollama-model}") String model) {
  this.mcp=mcp; this.ollama=RestClient.builder().baseUrl(url).build(); this.model=model; this.ollamaUrl=url;
 }
 @GetMapping("/health") public Map<String,String> health() { return Map.of("status","ok","mcp","connected"); }
 @GetMapping("/tools") public List<Map<String,Object>> tools() { return mcp.tools(); }

 @PostMapping("/tools/{name}")
 public Map<String,String> tool(@PathVariable String name,@RequestBody(required=false) Map<String,Object> args) {
  if (!Set.of("list_files","read_file","system_info").contains(name))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Tool requires the approval flow");
  return Map.of("result",mcp.call(name,args==null?Map.of():args));
 }
 @PostMapping("/commands/preview")
 public Map<String,String> preview(@RequestBody Map<String,String> body) {
  String cmd=body.getOrDefault("command","");
  if(!COMMANDS.contains(cmd)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Command unavailable");
  String id=UUID.randomUUID().toString(); approvals.put(id,cmd);
  return Map.of("id",id,"command",cmd,"message","Explicit approval required");
 }
 @PostMapping("/commands/{id}/approve")
 public Map<String,String> approve(@PathVariable String id) {
  String cmd=approvals.remove(id);
  if(cmd==null) throw new ResponseStatusException(HttpStatus.GONE,"Approval expired or already used");
  return Map.of("result",mcp.call("run_command",Map.of("command",cmd)));
 }
 @PostMapping("/chat")
 public Map<String,String> chat(@RequestBody Map<String,String> body) {
  String prompt=body.getOrDefault("message","").trim();
  if(prompt.isEmpty()||prompt.length()>4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Message length must be 1-4000");
  String system="You are a local developer assistant. Explain commands and tools, but never claim to have executed them. You cannot execute tools. Terminal actions require explicit GUI approval. Never advise bypassing approval.";
  try {
   Map<?,?> response=ollama.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
    .body(Map.of("model",model,"stream",false,"messages",List.of(
      Map.of("role","system","content",system),Map.of("role","user","content",prompt))))
    .retrieve().body(Map.class);
   Object msg=response==null?null:response.get("message");
   if(msg instanceof Map<?,?> m && m.get("content")!=null) return Map.of("answer",m.get("content").toString());
   return Map.of("answer","Ollama returned no message.");
  } catch(Exception e) {
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
     "Ollama is unreachable or model missing at " + ollamaUrl + " (model: " + model + "). Ensure 'ollama serve' is running and run 'ollama pull " + model + "'. Details: " + e.getMessage());
  }
 }
}
