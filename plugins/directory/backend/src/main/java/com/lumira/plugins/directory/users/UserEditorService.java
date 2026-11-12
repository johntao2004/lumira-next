package com.lumira.plugins.directory.users;

import com.lumira.platform.identity.api.UserProfileValuesPort;
import com.lumira.plugins.directory.organizations.OrganizationService;
import com.lumira.plugins.directory.roles.RoleService;
import java.util.Map;
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

    public record Change(
            Long version,
            Profile profile,
            Set<UUID> roleIds,
            Set<UUID> organizationIds,
            Long profileSchemaVersion,
            Map<String, String> profileValues) {}

    public record Saved(long version) {}

    private final UserService users;
    private final RoleService roles;
    private final OrganizationService organizations;
    private final UserProfileValuesPort profileValues;

    public UserEditorService(
            UserService users,
            RoleService roles,
            OrganizationService organizations,
            UserProfileValuesPort profileValues) {
        this.users = users;
        this.roles = roles;
        this.organizations = organizations;
        this.profileValues = profileValues;
    }

    @Transactional
    public Saved save(UUID id, Change input, String actor) {
        if (input == null
                || input.version() == null
                || (input.profile() == null
                        && input.roleIds() == null
                        && input.organizationIds() == null
                        && input.profileValues() == null)
                || (input.profileValues() != null && input.profileSchemaVersion() == null)
                || (input.profileValues() == null && input.profileSchemaVersion() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidUser");
        }

        long version = input.version();
        if (input.profileValues() != null) {
            users.requireUpdate(id, actor);
            version =
                    profileValues.writeForEditor(
                            id,
                            actor,
                            version,
                            input.profileSchemaVersion(),
                            input.profileValues());
        }
        if (input.profile() != null) {
            var profile = input.profile();
            var updated =
                    users.update(
                            id,
                            new UserService.UpdateUser(
                                    profile.displayName(), profile.enabled(), version),
                            actor);
            version = updated.version();
        }
        if (input.roleIds() != null) {
            var updated =
                    roles.assign(id, new RoleService.Assignment(version, input.roleIds()), actor);
            version = updated.version();
        }
        if (input.organizationIds() != null) {
            var updated =
                    organizations.assign(
                            id,
                            new OrganizationService.MembershipInput(
                                    version, input.organizationIds()),
                            actor);
            version = updated.version();
        }
        return new Saved(version);
    }
}
