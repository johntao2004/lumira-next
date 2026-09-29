package com.lumira.platform.roles;

import com.lumira.platform.common.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleService {
    public static final Set<String> PERMISSIONS=Set.of("platform:feedback:read","platform:feedback:create","platform:feedback:manage","platform:sensitive-words:read","platform:sensitive-words:write","platform:sensitive-words:import","platform:localization:read","platform:localization:write","platform:localization:import","platform:modules:read","platform:modules:write","platform:events:write","platform:users:read","platform:users:write","platform:roles:read","platform:roles:write","platform:organizations:read","platform:organizations:write","platform:audit:read","platform:dictionaries:read","platform:dictionaries:write","platform:settings:read","platform:settings:write","platform:navigation:read","platform:navigation:write","platform:files:read","platform:files:write","platform:notifications:read","platform:notifications:write");
    public record RoleInput(String code,String name,Boolean enabled,Long version,Set<String> authorities,String dataScope,
            Long fileQuotaBytesLimit,Long fileQuotaFilesLimit) {}
    public record Assignment(Long version,Set<UUID> roleIds) {}
    public record UserRoles(long version,List<UUID> roleIds) {}
    private final com.lumira.platform.common.AdministrationPort guard;
    private final RoleRepository roles;
    private final com.lumira.platform.common.UserDirectoryPort users;
    private final com.lumira.platform.common.AuditPort audit;
    private static final long MAX_SAFE_QUOTA = 9_007_199_254_740_991L;
    public RoleService(RoleRepository roles,com.lumira.platform.common.UserDirectoryPort users,com.lumira.platform.common.AdministrationPort guard,com.lumira.platform.common.AuditPort audit) { this.roles=roles;this.users=users;this.guard=guard;this.audit=audit; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<RoleRepository.Role> list(PageRequest p) { return new PageResult<>(roles.list(p),roles.count(p),p.page(),p.pageSize()); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RoleRepository.Role find(UUID id) { return roles.find(id); }
    private static void fail(HttpStatus status,String code) { throw new ResponseStatusException(status,code); }
    private Set<String> begin(String actor) {
        var held=guard.require(actor,"platform:roles:write");
        guard.requireGlobal(actor,"platform:roles:write");return held;
    }
    private void validate(RoleInput in,Set<String> held) {
        if(in.name()==null || in.name().isBlank() || in.name().strip().length()>200 || in.enabled()==null || in.authorities()==null || in.authorities().stream().anyMatch(Objects::isNull)) fail(HttpStatus.BAD_REQUEST,"invalidRole");
        if(!validQuota(in.fileQuotaBytesLimit()) || !validQuota(in.fileQuotaFilesLimit())) fail(HttpStatus.BAD_REQUEST,"invalidFileQuota");
        if(in.dataScope()!=null && !Set.of("SELF","ORGANIZATION","SUBTREE","ALL").contains(in.dataScope())) fail(HttpStatus.BAD_REQUEST,"invalidRole");
        if(!PERMISSIONS.containsAll(in.authorities())) fail(HttpStatus.BAD_REQUEST,"invalidRole");
        if(!held.containsAll(in.authorities())) fail(HttpStatus.FORBIDDEN,"forbidden");
    }
    private static boolean validQuota(Long value) {
        return value==null || (value>=0 && value<=MAX_SAFE_QUOTA);
    }
    private void protect() { if(!roles.hasAdministrators()) fail(HttpStatus.CONFLICT,"protectedAccount"); }
    @Transactional
    public RoleRepository.Role create(RoleInput in,String actor) {
        var held=begin(actor);validate(in,held);
        if(in.code()==null || !in.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")) fail(HttpStatus.BAD_REQUEST,"invalidRole");
        var id=UUID.randomUUID();roles.create(id,in.code(),in.name().strip(),in.enabled(),in.dataScope()==null?"SELF":in.dataScope(),in.fileQuotaBytesLimit(),in.fileQuotaFilesLimit());roles.authorities(id,in.authorities());
        audit.append(actor,"role.create",id);return roles.find(id);
    }
    @Transactional
    public RoleRepository.Role update(UUID id,RoleInput in,String actor) {
        var held=begin(actor);validate(in,held);var old=roles.find(id);
        if(in.version()==null || old.version()!=in.version()) fail(HttpStatus.CONFLICT,"staleRecord");
        if(!held.containsAll(old.authorities())) fail(HttpStatus.FORBIDDEN,"forbidden");
        roles.update(id,in.name().strip(),in.enabled(),in.dataScope()==null?old.dataScope():in.dataScope(),in.fileQuotaBytesLimit(),in.fileQuotaFilesLimit());roles.authorities(id,in.authorities());protect();
        audit.append(actor,"role.update",id);return roles.find(id);
    }
    @Transactional
    public void delete(UUID id,long version,String actor) {
        var held=begin(actor);var role=roles.find(id);
        if(role.version()!=version) fail(HttpStatus.CONFLICT,"staleRecord");
        if(!held.containsAll(role.authorities())) fail(HttpStatus.FORBIDDEN,"forbidden");
        if(roles.assigned(id)) fail(HttpStatus.CONFLICT,"roleInUse");
        roles.delete(id);audit.append(actor,"role.delete",id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public UserRoles userRoles(UUID id,String actor) {
        guard.requireUser(actor,id,"platform:roles:read");
        return new UserRoles(users.versionSnapshot(id),roles.userRoles(id));
    }
    @Transactional
    public UserRoles assign(UUID id,Assignment in,String actor) {
        var held=begin(actor);var version=users.version(id);
        if(in.roleIds()==null || in.roleIds().size()>100 || in.roleIds().stream().anyMatch(Objects::isNull)) fail(HttpStatus.BAD_REQUEST,"invalidRole");
        if(in.version()==null || version!=in.version()) fail(HttpStatus.CONFLICT,"staleRecord");
        var touched=new HashSet<>(roles.userRoles(id));touched.addAll(in.roleIds());
        for(var roleId:touched) if(!held.containsAll(roles.find(roleId).authorities())) fail(HttpStatus.FORBIDDEN,"forbidden");
        roles.assign(id,in.roleIds());protect();audit.append(actor,"user.roles",id);
        return new UserRoles(version+1,roles.userRoles(id));
    }
}
