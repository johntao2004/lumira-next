package com.lumira.platform.files;

import com.lumira.platform.identity.api.AvatarStatusPort;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AvatarRepository implements AvatarStatusPort {
    public record Avatar(UUID fileId,long version) {}
    private final JdbcClient jdbc;
    public AvatarRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public Avatar find(UUID owner,boolean lock) {
        if(lock) jdbc.sql("INSERT INTO platform_account_media(user_id) VALUES(:owner) ON CONFLICT DO NOTHING").param("owner",owner).update();
        return jdbc.sql("SELECT avatar_file_id,version FROM platform_account_media WHERE user_id=:owner"+(lock?" FOR UPDATE":""))
            .param("owner",owner).query((rs,n)->new Avatar(rs.getObject("avatar_file_id",UUID.class),rs.getLong("version")))
            .optional().orElse(new Avatar(null,0));
    }
    @Override public boolean hasAvatar(UUID owner) { return find(owner, false).fileId() != null; }
    public void update(UUID owner,UUID fileId) {
        jdbc.sql("UPDATE platform_account_media SET avatar_file_id=:file,version=version+1 WHERE user_id=:owner")
            .param("file",fileId).param("owner",owner).update();
    }
}
