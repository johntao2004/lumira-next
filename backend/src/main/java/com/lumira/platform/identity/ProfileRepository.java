package com.lumira.platform.identity;

import com.lumira.platform.identity.api.AccountOwnerPort;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ProfileRepository implements AccountOwnerPort {
    public record Profile(UUID id,String username,String displayName,String realName,String birthMonth,String region,long version) {}
    private final JdbcClient jdbc;
    public ProfileRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public Profile find(String username,boolean lock) {
        return jdbc.sql("SELECT id,username,display_name,real_name,birth_month,region,version FROM platform_user WHERE username=:name AND enabled"+(lock?" FOR UPDATE":""))
            .param("name",username).query((rs,n)->new Profile(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getString("real_name"),rs.getString("birth_month"),rs.getString("region"),rs.getLong("version")))
            .optional().orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    @Override public UUID id(String username, boolean lock) { return find(username, lock).id(); }
    public void update(String username,String displayName,String realName,String birthMonth,String region) {
        jdbc.sql("UPDATE platform_user SET display_name=:display,real_name=:real,birth_month=:birth,region=:region,version=version+1 WHERE username=:name")
            .param("display",displayName).param("real",realName).param("birth",birthMonth).param("region",region).param("name",username).update();
    }
}
