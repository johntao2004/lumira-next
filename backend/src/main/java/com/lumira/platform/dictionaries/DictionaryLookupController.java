package com.lumira.platform.dictionaries;
import com.lumira.platform.common.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/dictionaries/{code}/items")
public class DictionaryLookupController {
    private final DictionaryLookupService service;
    public DictionaryLookupController(DictionaryLookupService service) {this.service=service;}
    @GetMapping public PageResult<DictionaryLookupRepository.Option> list(@PathVariable String code,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search) {return service.list(code,new PageRequest(page,pageSize,search));}
    @GetMapping("/{id}") public DictionaryLookupRepository.Option find(@PathVariable String code,@PathVariable UUID id) {return service.find(code,id);}
}
