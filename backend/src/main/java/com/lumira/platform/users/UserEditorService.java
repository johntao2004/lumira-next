package com.lumira.platform.users;

import com.lumira.platform.organizations.OrganizationService;
import com.lumira.platform.roles.RoleService;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Coordinates the independently authorized user-management updates as one transaction. */
@Service
public class UserEditorService {
    public record Profile(String displayName, Boolean enabled) {}
    public record Change(Long version, Profile profile, Set<UUID> roleIds, Set<UUID> organizationIds) {}
    public record Saved(long version) {}

    private final UserService users;
    private final RoleService roles;
    private final OrganizationService organizations;

    public UserEditorService(UserService users, RoleService roles, OrganizationService organizations) {
        this.users = users;
        this.roles = roles;
        this.organizations = organizations;
    }

    @Transactional
    public Saved save(UUID id, Change input, String actor) {
        if (input == null || input.version() == null
                || (input.profile() == null && input.roleIds() == null && input.organizationIds() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidUser");
        }

        long version = input.version();
        if (input.profile() != null) {
            var profile = input.profile();
            var updated = users.update(id,
                    new UserService.UpdateUser(profile.displayName(), profile.enabled(), version), actor);
            version = updated.version();
        }
        if (input.roleIds() != null) {
            var updated = roles.assign(id, new RoleService.Assignment(version, input.roleIds()), actor);
            version = updated.version();
        }
        if (input.organizationIds() != null) {
            var updated = organizations.assign(id,
                    new OrganizationService.MembershipInput(version, input.organizationIds()), actor);
            version = updated.version();
        }
        return new Saved(version);
    }
}
