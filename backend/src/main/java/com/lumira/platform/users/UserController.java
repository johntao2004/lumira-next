package com.lumira.platform.users;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/users")
public class UserController {
    private final UserService service;
    private final UserEditorService editor;
    public UserController(UserService service, UserEditorService editor) {
        this.service = service;
        this.editor = editor;
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public UserSummary create(@RequestBody UserService.CreateUser input, org.springframework.security.core.Authentication auth) { return service.create(input,auth.getName()); }

    @GetMapping("/{id}")
    public UserSummary find(@PathVariable java.util.UUID id,
            org.springframework.security.core.Authentication auth) {
        return service.find(id, auth.getName());
    }

    @PutMapping("/{id}")
    public UserSummary update(@PathVariable java.util.UUID id,@RequestBody UserService.UpdateUser input,org.springframework.security.core.Authentication auth) { return service.update(id,input,auth.getName()); }

    @PutMapping("/{id}/editor")
    public UserEditorService.Saved saveEditor(@PathVariable java.util.UUID id,
            @RequestBody UserEditorService.Change input,org.springframework.security.core.Authentication auth) {
        return editor.save(id, input, auth.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable java.util.UUID id, @RequestParam long version,
            org.springframework.security.core.Authentication auth) {
        service.delete(id, version, auth.getName());
    }

    @GetMapping
    public PageResult<UserSummary> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,@RequestParam java.util.Map<String,String> parameters,org.springframework.security.core.Authentication auth) {
        return service.list(PageRequest.fromQueryParameters(page, pageSize, search, parameters),auth.getName());
    }

}
