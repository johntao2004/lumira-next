package com.lumira.platform.dictionaries;
import com.lumira.platform.common.*;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/platform/dictionaries/{dictionary}/items")
public class DictionaryItemController {
    private final DictionaryItemService service;
    public DictionaryItemController(DictionaryItemService service) {this.service=service;}
    @GetMapping public PageResult<DictionaryItemRepository.Item> list(@PathVariable UUID dictionary,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search,@RequestParam(required=false) String parent,@RequestParam java.util.Map<String,String> parameters) {return service.list(dictionary,PageRequest.fromQueryParameters(page,pageSize,search,parameters),parent);}
    @GetMapping("/{id}/ancestors") public java.util.List<DictionaryItemRepository.Item> ancestors(@PathVariable UUID dictionary,@PathVariable UUID id) {return service.ancestors(dictionary,id);}
    @GetMapping("/{id}") public DictionaryItemRepository.Item find(@PathVariable UUID dictionary,@PathVariable UUID id) {return service.find(dictionary,id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public DictionaryItemRepository.Item create(@PathVariable UUID dictionary,@RequestBody DictionaryItemService.Input input,Authentication auth) {return service.create(dictionary,input,auth.getName());}
    @PutMapping("/{id}") public DictionaryItemRepository.Item update(@PathVariable UUID dictionary,@PathVariable UUID id,@RequestBody DictionaryItemService.Input input,Authentication auth) {return service.update(dictionary,id,input,auth.getName());}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID dictionary,@PathVariable UUID id,@RequestParam long version,Authentication auth) {service.delete(dictionary,id,version,auth.getName());}
}
