package com.lumira.platform.users;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.identity.api.AuthenticationRequestContext;
import com.lumira.platform.identity.api.UserProfileValuesPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("directory")
@RequestMapping("/api/platform/users")
public class UserController {
    private final UserService service;
    private final UserEditorService editor;
    private final UserProfileValuesPort profileValues;

    public UserController(
            UserService service, UserEditorService editor, UserProfileValuesPort profileValues) {
        this.service = service;
        this.editor = editor;
        this.profileValues = profileValues;
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public UserSummary create(
            @RequestBody UserService.CreateUser input,
            org.springframework.security.core.Authentication auth) {
        return service.create(input, auth.getName());
    }

    @GetMapping("/{id}")
    public UserSummary find(
            @PathVariable java.util.UUID id,
            org.springframework.security.core.Authentication auth) {
        return service.find(id, auth.getName());
    }

    @GetMapping("/{id}/profile-values")
    public UserProfileValuesPort.EditorSnapshot profileValues(
            @PathVariable java.util.UUID id,
            org.springframework.security.core.Authentication auth) {
        service.find(id, auth.getName());
        return profileValues.readForEditor(id);
    }

    @PutMapping("/{id}")
    public UserSummary update(
            @PathVariable java.util.UUID id,
            @RequestBody UserService.UpdateUser input,
            org.springframework.security.core.Authentication auth) {
        return service.update(id, input, auth.getName());
    }

    @PutMapping("/{id}/editor")
    public UserEditorService.Saved saveEditor(
            @PathVariable java.util.UUID id,
            @RequestBody
                    @com.lumira.platform.common.BoundedBody(
                            maxBytes = 128 * 1024,
                            errorCode = "invalidUser")
                    UserEditorService.Change input,
            org.springframework.security.core.Authentication auth) {
        return editor.save(id, input, auth.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable java.util.UUID id,
            @RequestParam long version,
            org.springframework.security.core.Authentication auth) {
        service.delete(id, version, auth.getName());
    }

    @GetMapping
    public PageResult<UserSummary> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "") String search,
            @RequestParam java.util.Map<String, String> parameters,
            HttpServletRequest request,
            org.springframework.security.core.Authentication auth) {
        return service.list(
                PageRequest.fromQueryParameters(page, pageSize, search, parameters),
                auth.getName(),
                authorization(request));
    }

    private static com.lumira.platform.identity.api.RequestAuthorizationContext authorization(
            HttpServletRequest request) {
        var snapshot = AuthenticationRequestContext.current();
        return snapshot == null ? null : snapshot.authorization();
    }
}
