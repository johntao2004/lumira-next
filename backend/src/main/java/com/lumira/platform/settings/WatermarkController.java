package com.lumira.platform.settings;
import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class WatermarkController {
 public record View(long version,WatermarkSettings data){}
 public record Input(long version,WatermarkSettings data,String changeReason){}
 private final SettingStore store;private final ObjectMapper mapper;
 public WatermarkController(SettingStore store,ObjectMapper mapper){this.store=store;this.mapper=mapper;}
 @GetMapping("/api/platform/settings/watermark") public View read(){return store.find("watermark").map(s->new View(s.version(),mapper.readValue(s.payload(),WatermarkSettings.class))).orElseGet(()->new View(0,WatermarkSettings.defaults()));}
 @GetMapping("/api/public/watermark") public WatermarkSettings published(){return read().data();}
 @PutMapping("/api/platform/settings/watermark") public View write(@BoundedBody(maxBytes=32768,errorCode="invalidWatermark") @RequestBody Input input,Authentication auth){
  if(input.data()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidWatermark");input.data().validate();
  var saved=store.save("watermark",mapper.writeValueAsString(input.data()),auth.getName(),input.version(),input.changeReason());return new View(saved.version(),input.data());
 }
}
