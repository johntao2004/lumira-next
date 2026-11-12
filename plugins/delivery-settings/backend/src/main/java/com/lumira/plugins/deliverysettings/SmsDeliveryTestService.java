package com.lumira.plugins.deliverysettings;

import com.lumira.platform.common.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Authorizes and records the optional administration action before using the raw transport. */
@Service
public class SmsDeliveryTestService {
    public record Result(String result) {}

    private final SmsTransportProbePort probe;
    private final AdministrationPort guard;
    private final AuditPort audit;

    public SmsDeliveryTestService(
            SmsTransportProbePort probe, AdministrationPort guard, AuditPort audit) {
        this.probe = probe;
        this.guard = guard;
        this.audit = audit;
    }

    public Result sendLocalTest(String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.SMS_TEST);
        var result = probe.sendLocalTest();
        audit.append(actor, "sms.localTest." + result, UUID.randomUUID());
        return new Result(result);
    }
}
