package com.lumira.platform.settings;
import com.lumira.platform.common.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
public class AgreementController {
 public record View(long version,Agreements data){}
 public record Input(long version,Agreements data,String changeReason){}
 private final SettingStore store;private final ObjectMapper mapper;
 public AgreementController(SettingStore store,ObjectMapper mapper){this.store=store;this.mapper=mapper;}
 @GetMapping("/api/platform/settings/agreements") public View read(){return store.find("agreements").map(s->new View(s.version(),mapper.readValue(s.payload(),Agreements.class))).orElseGet(()->new View(0,Agreements.defaults()));}
 @GetMapping("/api/public/agreements") public Agreements published(){return read().data();}
 @PutMapping("/api/platform/settings/agreements") public View write(@BoundedBody(maxBytes=1024*1024,errorCode="invalidAgreements") @RequestBody Input input,Authentication auth){
  if(input.data()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidAgreements");input.data().validate();
  var saved=store.save("agreements",mapper.writeValueAsString(input.data()),auth.getName(),input.version(),input.changeReason());return new View(saved.version(),input.data());
 }
}
