package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("site-console")
public class AgreementController {
    public record View(long version, Agreements data) {}

    public record Input(long version, Agreements data, String changeReason) {}

    private final SettingDocuments documents;
    private final AgreementSnapshotService snapshots;
    private final AdministrationPort administration;

    public AgreementController(
            SettingDocuments documents,
            AgreementSnapshotService snapshots,
            AdministrationPort administration) {
        this.documents = documents;
        this.snapshots = snapshots;
        this.administration = administration;
    }

    @GetMapping("/api/platform/settings/agreements")
    public View read(Authentication auth) {
        administration.requirePageGlobal(auth.getName(), PlatformPageId.AGREEMENTS);
        return readDocument();
    }

    View readDocument() {
        var snapshot = documents.read("agreements", Agreements.class, Agreements::defaults);
        return new View(snapshot.version(), snapshot.data());
    }

    @PutMapping("/api/platform/settings/agreements")
    @Transactional
    public View write(
            @BoundedBody(maxBytes = 1024 * 1024, errorCode = "invalidAgreements") @RequestBody
                    Input input,
            Authentication auth) {
        snapshots.lockForPublication();
        var saved =
                documents.save(
                        "agreements",
                        input.version(),
                        input.data(),
                        input.changeReason(),
                        auth.getName(),
                        "invalidAgreements",
                        Agreements::validate);
        return new View(saved.version(), saved.data());
    }
}
