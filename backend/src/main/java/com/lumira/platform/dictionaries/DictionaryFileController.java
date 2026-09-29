package com.lumira.platform.dictionaries;

import java.util.List;
import com.lumira.platform.common.BoundedBody;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Raw uploads keep the byte budget independent of multipart/base64 overhead. */
@RestController
@com.lumira.platform.modules.ModuleEndpoint("dictionaries")
@RequestMapping("/api/platform/dictionaries")
public class DictionaryFileController {
    private final DictionaryFileParser parser;
    private final DictionaryDatasetService datasets;
    public DictionaryFileController(DictionaryFileParser parser,DictionaryDatasetService datasets){this.parser=parser;this.datasets=datasets;}
    public record Metadata(String code,String name,boolean enabled,String structure,String remark) {
        DictionaryDataset dataset(DictionaryFileParser.Parsed parsed){return new DictionaryDataset(1,code,name,enabled,structure,remark,parsed.items());}
    }
    public record Preview(String sha256,DictionaryDatasetService.Preview summary,List<DictionaryDataset.Entry> rows) {}
    @PostMapping(value="/file-preview",consumes="application/octet-stream")
    public Preview preview(@RequestParam String filename,@ModelAttribute Metadata metadata,
            @BoundedBody(maxBytes=DictionaryFileParser.MAX_BYTES,errorCode="dictionaryDatasetTooLarge") @RequestBody byte[] bytes) {
        var parsed=parser.parse(bytes,filename,metadata.structure());
        var summary=datasets.preview(metadata.dataset(parsed));
        return new Preview(parsed.sha256(),summary,parsed.items().stream().limit(20).toList());
    }
    @PostMapping(value="/file-import",consumes="application/octet-stream")
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryRepository.Dictionary importNew(@RequestParam String filename,@RequestParam String expectedSha256,
            @ModelAttribute Metadata metadata,
            @BoundedBody(maxBytes=DictionaryFileParser.MAX_BYTES,errorCode="dictionaryDatasetTooLarge") @RequestBody byte[] bytes,Authentication auth) {
        var parsed=parser.parse(bytes,filename,metadata.structure());
        if(!parsed.sha256().equals(expectedSha256))throw new ResponseStatusException(HttpStatus.CONFLICT,"dictionaryFileChanged");
        return datasets.importNew(metadata.dataset(parsed),auth.getName());
    }
}
