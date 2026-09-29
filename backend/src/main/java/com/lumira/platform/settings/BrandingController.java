package com.lumira.platform.settings;
import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("settings")
@RequestMapping("/api/platform/settings/branding")
public class BrandingController {
    public record View(long version,Branding data) {}
    public record Input(long version,Branding data,String changeReason) {}
    private final SettingStore store;private final ObjectMapper mapper;private final BrandingReader reader;
    public BrandingController(SettingStore store,ObjectMapper mapper,BrandingReader reader){this.store=store;this.mapper=mapper;this.reader=reader;}
    @GetMapping public BrandingReader.View read(){return reader.read();}
    @PutMapping public View write(@BoundedBody(maxBytes=32768,errorCode="invalidBranding") @RequestBody Input input,Authentication auth){
        if(input.data()==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"invalidBranding");
        input.data().validate();var saved=store.save("branding",mapper.writeValueAsString(input.data()),auth.getName(),input.version(),input.changeReason());return new View(saved.version(),input.data());
    }
}
