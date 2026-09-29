package com.lumira.platform.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Non-secret SMTP configuration. Credentials never enter this record or its history. */
public record SmtpSettings(Boolean enabled,String host,Integer port,String security,Boolean authentication,
                           String username,String fromAddress,String fromName) {
    public static SmtpSettings defaults(){return new SmtpSettings(false,"",587,"STARTTLS",true,"","","");}
    private static boolean text(String value,int max){return value!=null&&value.length()<=max&&value.codePoints().noneMatch(Character::isISOControl);}
    private static boolean host(String host){
        if(host.length()>253||host.isEmpty())return false;
        for(String label:host.split("\\.",-1))if(!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))return false;
        return true;
    }
    private static boolean mailbox(String address){
        int at=address.indexOf('@');if(at<1||at!=address.lastIndexOf('@')||at>64||address.length()>254)return false;
        String local=address.substring(0,at);
        return !local.startsWith(".")&&!local.endsWith(".")&&!local.contains("..")
            &&local.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+")&&host(address.substring(at+1));
    }
    public void validate(){
        if(enabled==null||authentication==null||port==null||port<1||port>65535
                ||!("STARTTLS".equals(security)||"TLS".equals(security))
                ||!text(host,253)||!text(username,320)||!text(fromAddress,254)||!text(fromName,120)
                ||(!host.isEmpty()&&!host(host))||(!fromAddress.isEmpty()&&!mailbox(fromAddress))
                ||(enabled&&(host.isEmpty()||fromAddress.isEmpty()||(authentication&&username.isBlank()))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidSmtp");
    }
    public boolean sameCredentialDestination(SmtpSettings other){
        return host.equalsIgnoreCase(other.host)&&port.equals(other.port)&&username.equals(other.username);
    }
}
