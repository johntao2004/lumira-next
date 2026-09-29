package com.lumira.platform.identity;

import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** High-entropy bearer token. Persist only its digest; never render it in status or logs. */
public final class VerificationToken {
    private static final SecureRandom RANDOM=new SecureRandom();
    private VerificationToken(){}
    public record Issued(String value,String digest){@Override public String toString(){return "VerificationToken[redacted]";}}
    public static Issued issue(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);try{String value=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);return new Issued(value,digest(value));}finally{Arrays.fill(bytes,(byte)0);}}
    public static String digest(String value){
        if(value==null||!value.matches("[A-Za-z0-9_-]{43}"))return "";
        byte[] decoded=Base64.getUrlDecoder().decode(value);
        try{
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value))return "";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Digest unavailable");}
        finally{Arrays.fill(decoded,(byte)0);}
    }
}
