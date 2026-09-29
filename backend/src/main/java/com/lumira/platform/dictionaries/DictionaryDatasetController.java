package com.lumira.platform.dictionaries;
import java.util.UUID;
import com.lumira.platform.common.BoundedBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/platform/dictionaries")
public class DictionaryDatasetController {
    private final DictionaryDatasetService service;
    public DictionaryDatasetController(DictionaryDatasetService service) {this.service=service;}
    @GetMapping("/{id}/export") public ResponseEntity<DictionaryDataset> export(@PathVariable UUID id) {return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"dictionary-"+id+".json\"").body(service.export(id));}
    @PostMapping("/preview") public DictionaryDatasetService.Preview preview(@BoundedBody(maxBytes=20*1024*1024,errorCode="dictionaryDatasetTooLarge") @RequestBody DictionaryDataset dataset) {return service.preview(dataset);}
    @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED) public DictionaryRepository.Dictionary importNew(@BoundedBody(maxBytes=20*1024*1024,errorCode="dictionaryDatasetTooLarge") @RequestBody DictionaryDataset dataset,Authentication auth) {return service.importNew(dataset,auth.getName());}
}
