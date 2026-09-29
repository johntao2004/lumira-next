package com.lumira.platform.settings;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
@Service
public class BrandingReader {
    public record View(long version,Branding data) {}
    private final SettingStore store;private final ObjectMapper mapper;
    public BrandingReader(SettingStore store,ObjectMapper mapper){this.store=store;this.mapper=mapper;}
    public View read(){return store.find("branding").map(s->new View(s.version(),mapper.readValue(s.payload(),Branding.class))).orElseGet(()->new View(0,Branding.defaults()));}
}
