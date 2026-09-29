package com.lumira.platform.organizations;

import com.lumira.platform.common.*;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.UserDirectoryPort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrganizationService {
    public record Input(UUID parentId,String code,String name,Boolean enabled,Long version) {}
    public record MembershipInput(Long version,Set<UUID> organizationIds) {}
    public record Membership(long version,List<UUID> organizationIds) {}
    private final OrganizationRepository repository;
    private final AdministrationPort guard;
    private final UserDirectoryPort users;
    private final AuditPort audit;
    public OrganizationService(OrganizationRepository repository,AdministrationPort guard,UserDirectoryPort users,AuditPort audit) { this.repository=repository;this.guard=guard;this.users=users;this.audit=audit; }
    private static void fail(HttpStatus status,String code) { throw new ResponseStatusException(status,code); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResult<OrganizationRepository.Organization> list(PageRequest p,String parent,String actor) {
        var filter=ParentFilter.parse(parent);
        if(filter.id()!=null)repository.findVisible(filter.id(),actor);
        return new PageResult<>(repository.list(p,filter.active(),filter.id(),actor),repository.count(p,filter.active(),filter.id(),actor),p.page(),p.pageSize());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<OrganizationRepository.Organization> ancestors(UUID id,String actor) { repository.findVisible(id,actor);return repository.ancestors(id,actor); }
    @Transactional(readOnly=true) public OrganizationRepository.Organization find(UUID id) { return repository.find(id); }
    @Transactional(readOnly=true) public OrganizationRepository.Organization find(UUID id,String actor) { return repository.findVisible(id,actor); }
    private void validate(UUID id,Input in) {
        if(in.name()==null || in.name().isBlank() || in.name().strip().length()>200 || in.enabled()==null) fail(HttpStatus.BAD_REQUEST,"invalidOrganization");
        if(in.parentId()!=null) {
            repository.find(in.parentId());
            if(in.parentId().equals(id) || repository.cycles(id,in.parentId())) fail(HttpStatus.CONFLICT,"organizationCycle");
        }
    }
    @Transactional
    public OrganizationRepository.Organization create(Input in,String actor) {
        guard.require(actor,"platform:organizations:write");guard.requireGlobal(actor,"platform:organizations:write");var id=UUID.randomUUID();validate(id,in);
        if(in.code()==null || !in.code().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")) fail(HttpStatus.BAD_REQUEST,"invalidOrganization");
        repository.create(id,in.parentId(),in.code(),in.name().strip(),in.enabled());audit.append(actor,"organization.create",id);return repository.find(id);
    }
    @Transactional
    public OrganizationRepository.Organization update(UUID id,Input in,String actor) {
        guard.require(actor,"platform:organizations:write");guard.requireGlobal(actor,"platform:organizations:write");var old=repository.find(id);
        if(in.version()==null || old.version()!=in.version()) fail(HttpStatus.CONFLICT,"staleRecord");
        validate(id,in);repository.update(id,in.parentId(),in.name().strip(),in.enabled());audit.append(actor,"organization.update",id);return repository.find(id);
    }
    @Transactional
    public void delete(UUID id,long version,String actor) {
        guard.require(actor,"platform:organizations:write");guard.requireGlobal(actor,"platform:organizations:write");var old=repository.find(id);
        if(old.version()!=version) fail(HttpStatus.CONFLICT,"staleRecord");
        if(repository.inUse(id)) fail(HttpStatus.CONFLICT,"organizationInUse");
        repository.delete(id);audit.append(actor,"organization.delete",id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Membership memberships(UUID id,String actor) {
        guard.requireUser(actor,id,"platform:organizations:read");
        return new Membership(users.versionSnapshot(id),repository.memberships(id));
    }
    @Transactional
    public Membership assign(UUID id,MembershipInput input,String actor) {
        guard.require(actor,"platform:organizations:write");guard.requireGlobal(actor,"platform:organizations:write");var version=users.version(id);
        if(input.version()==null || input.version()!=version) fail(HttpStatus.CONFLICT,"staleRecord");
        if(input.organizationIds()==null || input.organizationIds().size()>100 || input.organizationIds().stream().anyMatch(Objects::isNull)) fail(HttpStatus.BAD_REQUEST,"invalidOrganization");
        for(var organizationId:input.organizationIds()) repository.find(organizationId);
        repository.memberships(id,input.organizationIds());audit.append(actor,"user.organizations",id);
        return new Membership(version+1,repository.memberships(id));
    }
}
