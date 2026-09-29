package com.lumira.platform.organizations;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageFilterSql;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class OrganizationRepository {
    public record Organization(UUID id,UUID parentId,String code,String name,boolean enabled,long version) {}
    private final JdbcClient jdbc;
    private static final String FILTER=" WHERE id IN (SELECT organization_id FROM platform_visible_organizations(:actor,'platform:organizations:read')) AND (:search='' OR strpos(lower(code),lower(:search))>0 OR strpos(lower(name),lower(:search))>0)";
    public OrganizationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    private Organization row(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Organization(rs.getObject("id",UUID.class),rs.getObject("parent_id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getBoolean("enabled"),rs.getLong("version"));
    }
    private String parentFilter(boolean hierarchy,UUID parent) {
        return !hierarchy ? "" : parent==null ? " AND parent_id IS NULL" : " AND parent_id=:parent";
    }
    private JdbcClient.StatementSpec filtered(String sql,String suffix,PageRequest p,boolean hierarchy,UUID parent,String actor) {
        var filters=PageFilterSql.build(p,Map.of("code","code","name","name","enabled","enabled"));
        var query=jdbc.sql(sql+filters.sql()+suffix).param("actor",actor).param("search",p.search()).params(filters.parameters());
        return hierarchy && parent!=null ? query.param("parent",parent) : query;
    }
    public long count(PageRequest p,boolean hierarchy,UUID parent,String actor) {
        return filtered("SELECT count(*) FROM platform_organization"+FILTER+parentFilter(hierarchy,parent),"",p,hierarchy,parent,actor).query(Long.class).single();
    }
    public List<Organization> list(PageRequest p,boolean hierarchy,UUID parent,String actor) {
        return filtered("SELECT * FROM platform_organization"+FILTER+parentFilter(hierarchy,parent)," ORDER BY code,id LIMIT :limit OFFSET :offset",p,hierarchy,parent,actor)
            .param("limit",p.pageSize()).param("offset",p.offset()).query((rs,n)->row(rs)).list();
    }
    public List<Organization> ancestors(UUID id,String actor) {
        return jdbc.sql("WITH RECURSIVE ancestors AS (SELECT o.*,0 AS depth FROM platform_organization o WHERE id=:id UNION ALL SELECT o.*,a.depth+1 FROM platform_organization o JOIN ancestors a ON o.id=a.parent_id) SELECT a.* FROM ancestors a WHERE a.id IN (SELECT organization_id FROM platform_visible_organizations(:actor,'platform:organizations:read')) ORDER BY depth DESC")
            .param("id",id).param("actor",actor).query((rs,n)->row(rs)).list();
    }
    public Organization find(UUID id) {
        return jdbc.sql("SELECT * FROM platform_organization WHERE id=:id").param("id",id).query((rs,n)->row(rs)).optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public Organization findVisible(UUID id,String actor) {
        return jdbc.sql("SELECT * FROM platform_organization WHERE id=:id AND id IN (SELECT organization_id FROM platform_visible_organizations(:actor,'platform:organizations:read'))")
            .param("id",id).param("actor",actor).query((rs,n)->row(rs)).optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public boolean cycles(UUID id,UUID parent) {
        return jdbc.sql("WITH RECURSIVE ancestors AS (SELECT id,parent_id FROM platform_organization WHERE id=:parent UNION SELECT o.id,o.parent_id FROM platform_organization o JOIN ancestors a ON o.id=a.parent_id) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=:id)")
            .param("id",id).param("parent",parent).query(Boolean.class).single();
    }
    public void create(UUID id,UUID parent,String code,String name,boolean enabled) {
        jdbc.sql("INSERT INTO platform_organization(id,parent_id,code,name,enabled) VALUES(:id,:parent,:code,:name,:enabled)").param("id",id).param("parent",parent).param("code",code).param("name",name).param("enabled",enabled).update();
    }
    public void update(UUID id,UUID parent,String name,boolean enabled) {
        jdbc.sql("UPDATE platform_organization SET parent_id=:parent,name=:name,enabled=:enabled,version=version+1 WHERE id=:id").param("id",id).param("parent",parent).param("name",name).param("enabled",enabled).update();
    }
    public List<UUID> memberships(UUID userId) {
        return jdbc.sql("SELECT organization_id FROM platform_user_organization WHERE user_id=:id ORDER BY organization_id").param("id",userId).query(UUID.class).list();
    }
    public void memberships(UUID userId,Set<UUID> ids) {
        jdbc.sql("DELETE FROM platform_user_organization WHERE user_id=:id").param("id",userId).update();
        for(var id:ids) jdbc.sql("INSERT INTO platform_user_organization(user_id,organization_id) VALUES(:user,:org)").param("user",userId).param("org",id).update();
        jdbc.sql("UPDATE platform_user SET version=version+1 WHERE id=:id").param("id",userId).update();
    }
    public boolean inUse(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform_organization WHERE parent_id=:id) OR EXISTS(SELECT 1 FROM platform_user_organization WHERE organization_id=:id)").param("id",id).query(Boolean.class).single();
    }
    public void delete(UUID id) { jdbc.sql("DELETE FROM platform_organization WHERE id=:id").param("id",id).update(); }
}
