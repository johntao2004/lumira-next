package com.lumira.platform.files;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.DeadlineInputStream;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AvatarService {
    private final AvatarRepository avatars;
    private final AccountOwnerPort accounts;
    private final FileRepository files;
    private final FileService storage;
    private final AuditPort audit;
    private final ProfileSchemaPort schemas;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    public AvatarService(AvatarRepository avatars,AccountOwnerPort accounts,FileRepository files,FileService storage,AuditPort audit,ProfileSchemaPort schemas,org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.avatars=avatars;this.accounts=accounts;this.files=files;this.storage=storage;this.audit=audit;this.schemas=schemas;this.jdbc=jdbc;
    }
    private void requireEditable(boolean remove){
        var rule=schemas.read().data().avatar();
        if(rule.isPresent()&&!rule.get().visible())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"forbidden");
        if(remove&&rule.isPresent()&&rule.get().required())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"avatarRequired");
    }
    private void lockSchema(){jdbc.sql("SELECT pg_advisory_xact_lock(481026)").query(rs->{rs.next();return 0;});}
    public AvatarRepository.Avatar find(String actor) { return avatars.find(accounts.id(actor,false),false); }
    private static void checkVersion(AvatarRepository.Avatar current,long expected) {
        if(expected<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidVersion");
        if(current.version()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"staleRecord");
    }
    public AvatarRepository.Avatar upload(String actor,long version,InputStream source)throws IOException {
        checkVersion(find(actor),version);
        requireEditable(false);
        var image=ImageNormalizer.normalize(new DeadlineInputStream(source,Duration.ofMinutes(15)));
        storage.uploadImage(actor,image,()->{lockSchema();requireEditable(false);},entry->{
            var current=avatars.find(entry.ownerId(),true);
            checkVersion(current,version);
            avatars.update(entry.ownerId(),entry.id());
            retire(actor,entry.ownerId(),current.fileId());
            audit.append(actor,"account.avatarUpdated",entry.ownerId());
        });
        // This upload committed version+1; another tab may already have changed the current avatar.
        return find(actor);
    }
    @Transactional
    public AvatarRepository.Avatar remove(String actor,long version) {
        lockSchema();requireEditable(true);
        var owner=accounts.id(actor,true);
        var current=avatars.find(owner,true);checkVersion(current,version);
        if(current.fileId()==null)return current;
        avatars.update(owner,null);
        retire(actor,owner,current.fileId());
        audit.append(actor,"account.avatarRemoved",owner);
        return new AvatarRepository.Avatar(null,current.version()+1);
    }
    private void retire(String actor,UUID owner,UUID id) {
        if(id==null)return;
        var old=files.findOwned(id,owner).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"fileStateConflict"));
        if(!files.requestDelete(id,owner,old.version()))throw new ResponseStatusException(HttpStatus.CONFLICT,"fileStateConflict");
        audit.append(actor,"file.deleteRequested",id);
        // Existing scheduled cleanup removes bytes and releases quota after commit.
    }
    public FileRepository.Entry image(String actor,long version) {
        var current=find(actor);
        if(current.fileId()==null || current.version()!=version)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"fileNotFound");
        return storage.find(actor,current.fileId());
    }
}
