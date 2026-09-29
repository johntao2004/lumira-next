package com.lumira.platform.feedback;
import com.lumira.platform.common.*;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/feedback")
@ModuleEndpoint("feedback")
public class FeedbackController {
 private final FeedbackService service;
 private final UploadRateLimitPort limiter;
 private final ClientAddressResolver addresses;
 private final FileContentPort files;
 public FeedbackController(FeedbackService service,UploadRateLimitPort limiter,ClientAddressResolver addresses,FileContentPort files){this.service=service;this.limiter=limiter;this.addresses=addresses;this.files=files;}
 @GetMapping public PageResult<FeedbackStore.Summary> mine(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search,@RequestParam(required=false) String status,@RequestParam(required=false) String priority,@RequestParam(required=false) String category,@RequestParam java.util.Map<String,String> parameters){PageRequest request=PageRequest.fromQueryParameters(page,pageSize,search,parameters);return service.mine(auth.getName(),request,request.filters().getOrDefault("status",status),request.filters().getOrDefault("priority",priority),request.filters().getOrDefault("category",category));}
 @GetMapping("/{id}") public FeedbackStore.Detail mine(Authentication auth,@PathVariable UUID id){return service.mine(auth.getName(),id);}
 @GetMapping("/options") public FeedbackCatalog.Catalog options(Authentication auth){return service.options(auth.getName());}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public FeedbackStore.Detail create(Authentication auth,@BoundedBody(maxBytes=810000,errorCode="invalidFeedback") @RequestBody FeedbackService.Create input){return service.create(auth.getName(),input);}
 @PostMapping(path="/images",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
 @ResponseStatus(HttpStatus.CREATED)
 public FeedbackService.ImageUpload uploadImage(@RequestParam String filename,Authentication auth,HttpServletRequest request)throws IOException{
  if(!limiter.allow(auth.getName(),addresses.resolve(request)))throw new RateLimitExceededException("uploadRateLimited");
  try{return service.uploadImage(auth.getName(),filename,request.getInputStream(),request.getContentLengthLong());}
  catch(FileContentPort.InvalidImage error){throw new org.springframework.web.server.ResponseStatusException("imageTooLarge".equals(error.getMessage())?HttpStatus.PAYLOAD_TOO_LARGE:HttpStatus.BAD_REQUEST,error.getMessage());}
 }
 @GetMapping("/images/{id}")
 public ResponseEntity<InputStreamResource> image(@PathVariable UUID id,Authentication auth)throws IOException{
  var entry=service.image(auth.getName(),id);
  return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(entry.sizeBytes())
   .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
   .body(new InputStreamResource(files.openContentImage(entry)));
 }
 @GetMapping("/manage") public PageResult<FeedbackStore.Summary> managed(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search,@RequestParam(required=false) String status,@RequestParam(required=false) String priority,@RequestParam(required=false) String category,@RequestParam java.util.Map<String,String> parameters){PageRequest request=PageRequest.fromQueryParameters(page,pageSize,search,parameters);return service.managed(auth.getName(),request,request.filters().getOrDefault("status",status),request.filters().getOrDefault("priority",priority),request.filters().getOrDefault("category",category));}
 @GetMapping("/manage/{id}") public FeedbackStore.Detail managed(Authentication auth,@PathVariable UUID id){return service.managed(auth.getName(),id);}
 @PutMapping("/manage/{id}") public FeedbackStore.Detail handle(Authentication auth,@PathVariable UUID id,@BoundedBody(maxBytes=20000,errorCode="invalidFeedback") @RequestBody FeedbackService.Handle input){return service.handle(auth.getName(),id,input);}
}
