package com.lumira.platform.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.AddressException;

/** Internal plain-text, single-recipient message. No arbitrary headers or attachments. */
public record OutgoingMail(String recipient,String subject,String text) {
    public OutgoingMail {
        if(recipient==null||recipient.length()>254||recipient.codePoints().anyMatch(Character::isISOControl)
            ||subject==null||subject.isBlank()||subject.length()>200||subject.codePoints().anyMatch(Character::isISOControl)
            ||text==null||text.length()>65536||text.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid outgoing mail");
        try{
            var parsed=new InternetAddress(recipient,true);parsed.validate();
            if(parsed.isGroup()||parsed.getPersonal()!=null||!recipient.equals(parsed.getAddress())||!recipient.contains("@"))throw new AddressException();
        }catch(AddressException e){throw new IllegalArgumentException("Invalid outgoing mail");}
    }
    @Override public String toString(){return "OutgoingMail[redacted]";}
}
