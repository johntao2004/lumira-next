package com.lumira.platform.common;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
/** Omitted means all records; root means no parent; UUID means direct children. */
public record ParentFilter(boolean active,UUID id) {
    public static ParentFilter parse(String value) {
        if(value==null)return new ParentFilter(false,null);
        if(value.equals("root"))return new ParentFilter(true,null);
        try {return new ParentFilter(true,UUID.fromString(value));}
        catch(IllegalArgumentException error) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidParent");}
    }
    public String predicate() {return !active?"":id==null?" AND parent_id IS NULL":" AND parent_id=:parent";}
    public JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement) {return id==null?statement:statement.param("parent",id);}
}
