package com.lumira.platform.dictionaries;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.dictionaries.api.DictionaryResponse;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/platform/dictionaries")
public class DictionaryDatasetController {
    private final DictionaryDatasetService service;

    public DictionaryDatasetController(DictionaryDatasetService service) {
        this.service = service;
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<DictionaryDataset> export(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"dictionary-" + id + ".json\"")
                .body(service.export(id, auth.getName()));
    }

    @PostMapping("/preview")
    public DictionaryDatasetService.Preview preview(
            @BoundedBody(maxBytes = 20 * 1024 * 1024, errorCode = "dictionaryDatasetTooLarge")
                    @RequestBody
                    DictionaryDataset dataset,
            Authentication auth) {
        return service.preview(dataset, auth.getName());
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryResponse importNew(
            @BoundedBody(maxBytes = 20 * 1024 * 1024, errorCode = "dictionaryDatasetTooLarge")
                    @RequestBody
                    DictionaryDataset dataset,
            Authentication auth) {
        return DictionaryResponse.from(service.importNew(dataset, auth.getName()));
    }
}
