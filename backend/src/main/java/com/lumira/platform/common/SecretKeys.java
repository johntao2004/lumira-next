package com.lumira.platform.common;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deployment key ring. An absent ring disables secret operations, not unrelated platform features. */
@Component
public final class SecretKeys {
    private static final Logger log = LoggerFactory.getLogger(SecretKeys.class);
    private final SecretCipher cipher;
    public SecretKeys(@Value("${LUMIRA_SECRET_KEYS:}") String encodedKeys,
                      @Value("${LUMIRA_SECRET_ACTIVE_KEY:primary}") String activeKey) {
        if(encodedKeys==null||encodedKeys.isBlank()){
            log.warn("LUMIRA_SECRET_KEYS is not configured; secret-dependent features will remain unavailable");
            cipher=null;return;
        }
        var decoded=new HashMap<String,byte[]>();
        try{
            if(encodedKeys.length()>2048)throw new IllegalArgumentException();
            for(String entry:encodedKeys.split(",",-1)){
                String[] pair=entry.split("=",2);
                if(pair.length!=2||!pair[0].matches("[A-Za-z0-9_-]{1,32}")||decoded.containsKey(pair[0]))throw new IllegalArgumentException();
                byte[] key=Base64.getDecoder().decode(pair[1]);
                decoded.put(pair[0],key);
                if(!Base64.getEncoder().encodeToString(key).equals(pair[1]))throw new IllegalArgumentException();
            }
            cipher=new SecretCipher(decoded,activeKey);
        }catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid secret key configuration");}
        finally{decoded.values().forEach(key->Arrays.fill(key,(byte)0));}
    }
    public boolean configured(){return cipher!=null;}
    public SecretCipher require(){
        if(cipher==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"secretKeyUnavailable");
        return cipher;
    }
}
