package com.lumira.platform.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned authenticated envelopes for server-owned secrets; never accepts plaintext as ciphertext. */
public final class SecretCipher {
    public static final int MAXIMUM_PLAINTEXT_BYTES=65536;
    private static final int NONCE_BYTES=12;
    private static final int TAG_BYTES=16;
    private static final int MAXIMUM_ENVELOPE_CHARS=90000;
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    private final Map<String,SecretKey> keys;
    private final String activeKey;
    private final SecureRandom random=new SecureRandom();

    /** Keys must be random 256-bit keys supplied by deployment configuration, not passwords. */
    public SecretCipher(Map<String,byte[]> keyRing,String activeKey) {
        if(keyRing==null||keyRing.isEmpty()||keyRing.size()>16||!validId(activeKey)||!keyRing.containsKey(activeKey))
            throw new IllegalArgumentException("Invalid secret key configuration");
        var copy=new HashMap<String,SecretKey>();
        keyRing.forEach((id,key)->{
            if(!validId(id)||key==null||key.length!=32)throw new IllegalArgumentException("Invalid secret key configuration");
            copy.put(id,new SecretKeySpec(key,"AES"));
        });
        this.keys=Map.copyOf(copy);this.activeKey=activeKey;
    }
    private static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{1,32}");}
    private static byte[] associatedData(String prefix,String context){
        // Canonical identifiers avoid ambiguous Unicode encodings. Include the record ID and field.
        if(context==null||!context.matches("[A-Za-z0-9_:/.-]{1,512}"))
            throw new IllegalArgumentException("Invalid secret context");
        return (prefix+"\n"+context).getBytes(StandardCharsets.UTF_8);
    }
    public String encrypt(String context,byte[] plaintext){
        if(plaintext==null||plaintext.length>MAXIMUM_PLAINTEXT_BYTES)throw new IllegalArgumentException("Invalid secret size");
        String prefix="v1:"+activeKey;
        byte[] aad=associatedData(prefix,context),nonce=new byte[NONCE_BYTES];random.nextBytes(nonce);
        try{
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,keys.get(activeKey),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);
            byte[] encrypted=cipher.doFinal(plaintext);
            byte[] envelope=new byte[nonce.length+encrypted.length];
            System.arraycopy(nonce,0,envelope,0,nonce.length);System.arraycopy(encrypted,0,envelope,nonce.length,encrypted.length);
            return prefix+":"+ENCODER.encodeToString(envelope);
        }catch(GeneralSecurityException e){throw new IllegalStateException("Secret encryption unavailable");}
    }
    /** Callers own the returned plaintext and should clear its bytes after use. */
    public byte[] decrypt(String context,String envelope){
        if(envelope==null||envelope.length()>MAXIMUM_ENVELOPE_CHARS)throw invalid();
        String[] parts=envelope.split(":",-1);
        if(parts.length!=3||!parts[0].equals("v1")||!validId(parts[1])||!keys.containsKey(parts[1]))throw invalid();
        byte[] aad=associatedData(parts[0]+":"+parts[1],context);
        try{
            byte[] combined=DECODER.decode(parts[2]);
            if(combined.length<NONCE_BYTES+TAG_BYTES||combined.length>NONCE_BYTES+TAG_BYTES+MAXIMUM_PLAINTEXT_BYTES
                    ||!ENCODER.encodeToString(combined).equals(parts[2]))throw invalid();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,keys.get(parts[1]),new GCMParameterSpec(128,Arrays.copyOf(combined,NONCE_BYTES)));
            cipher.updateAAD(aad);
            return cipher.doFinal(combined,NONCE_BYTES,combined.length-NONCE_BYTES);
        }catch(GeneralSecurityException|IllegalArgumentException e){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("Secret cannot be decrypted");}
}
