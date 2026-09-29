package com.lumira.platform.files;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.RateLimitExceededException;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("files")
@RequestMapping("/api/account/avatar")
public class AvatarController {
    private final AvatarService avatars;
    private final FileService files;
    private final FileUploadRateLimiter limiter;
    private final ClientAddressResolver addresses;
    public AvatarController(AvatarService avatars,FileService files,FileUploadRateLimiter limiter,ClientAddressResolver addresses) {
        this.avatars=avatars;this.files=files;this.limiter=limiter;this.addresses=addresses;
    }
    @GetMapping public AvatarRepository.Avatar find(Authentication actor) {return avatars.find(actor.getName());}
    @PostMapping(consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public AvatarRepository.Avatar upload(@RequestParam long version,Authentication actor,HttpServletRequest request)throws IOException {
        if(!limiter.allow(actor.getName(),addresses.resolve(request)))throw new RateLimitExceededException("uploadRateLimited");
        if(request.getContentLengthLong()>ImageNormalizer.MAXIMUM_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"imageTooLarge");
        try{return avatars.upload(actor.getName(),version,request.getInputStream());}
        catch(ImageNormalizer.InvalidImage error){throw new ResponseStatusException(error.getMessage().equals("imageTooLarge")?HttpStatus.PAYLOAD_TOO_LARGE:HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    @DeleteMapping public AvatarRepository.Avatar remove(@RequestParam long version,Authentication actor) {return avatars.remove(actor.getName(),version);}
    @GetMapping("/content") public ResponseEntity<InputStreamResource> image(@RequestParam long version,Authentication actor)throws IOException {
        var entry=avatars.image(actor.getName(),version);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(entry.sizeBytes())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
            .body(new InputStreamResource(files.open(entry)));
    }
}
