package com.lumira.platform.dictionaries;
import com.lumira.platform.common.*;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/platform/dictionaries")
public class DictionaryController {
    private final DictionaryService service;
    public DictionaryController(DictionaryService service) {this.service=service;}
    @GetMapping public PageResult<DictionaryRepository.Dictionary> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search,@RequestParam java.util.Map<String,String> parameters) {return service.list(PageRequest.fromQueryParameters(page,pageSize,search,parameters));}
    @GetMapping("/{id}") public DictionaryRepository.Dictionary find(@PathVariable UUID id) {return service.find(id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public DictionaryRepository.Dictionary create(@RequestBody DictionaryService.Input input,Authentication auth) {return service.create(input,auth.getName());}
    @PutMapping("/{id}") public DictionaryRepository.Dictionary update(@PathVariable UUID id,@RequestBody DictionaryService.Input input,Authentication auth) {return service.update(id,input,auth.getName());}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id,@RequestParam long version,Authentication auth) {service.delete(id,version,auth.getName());}
}
