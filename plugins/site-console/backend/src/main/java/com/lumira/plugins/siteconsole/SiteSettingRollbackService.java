package com.lumira.plugins.siteconsole;

import com.lumira.platform.common.*;
import com.lumira.platform.settings.*;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Site-owned publication and rollback workflows; storage keeps immutable revision history. */
@Service
public class SiteSettingRollbackService {
    public record Input(long version, long sourceVersion, String changeReason) {}

    public record View(
            long version,
            Object data,
            Boolean passwordConfigured,
            Boolean credentialConfigured,
            Boolean encryptionConfigured) {}

    private static final Set<String> SECTIONS =
            Set.of("branding", "watermark", "agreements", "maintenance");
    private final SettingStore store;
    private final ObjectMapper mapper;
    private final AdministrationPort guard;
    private final MaintenanceService maintenance;
    private final BrandingAssetReferences assets;
    private final AgreementSnapshotService agreements;

    public SiteSettingRollbackService(
            SettingStore store,
            ObjectMapper mapper,
            AdministrationPort guard,
            MaintenanceService maintenance,
            BrandingAssetReferences assets,
            AgreementSnapshotService agreements) {
        this.store = store;
        this.mapper = mapper;
        this.guard = guard;
        this.maintenance = maintenance;
        this.assets = assets;
        this.agreements = agreements;
    }

    @Transactional
    public View rollback(String section, Input input, String actor) {
        if (!SECTIONS.contains(section))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settingNotFound");
        if (input == null || input.version() < 0 || input.sourceVersion() < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidSettingRevision");
        // Match normal publication: agreement lock precedes the administration/session boundary.
        if (section.equals("agreements")) agreements.lockForPublication();
        guard.requireOperationGlobal(actor, SiteSettingsSections.operation(section));
        var revision =
                store.revision(section, input.sourceVersion())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "settingRevisionNotFound"));
        Object data =
                switch (section) {
                    case "branding" -> mapper.readValue(revision.payload(), Branding.class);
                    case "watermark" ->
                            mapper.readValue(revision.payload(), WatermarkSettings.class);
                    case "agreements" -> mapper.readValue(revision.payload(), Agreements.class);
                    case "maintenance" ->
                            mapper.readValue(revision.payload(), MaintenanceSettings.class);
                    default -> throw new IllegalStateException("Unknown section");
                };
        if (data instanceof MaintenanceSettings value) {
            var saved =
                    maintenance.save(
                            value,
                            actor,
                            input.version(),
                            input.changeReason(),
                            input.sourceVersion());
            return new View(saved.version(), saved.data(), null, null, null);
        }
        if (data instanceof Branding value) value.validate();
        if (data instanceof WatermarkSettings value) value.validate();
        if (data instanceof Agreements value) value.validate();
        var saved =
                store.save(
                        section,
                        revision.payload(),
                        actor,
                        input.version(),
                        input.changeReason(),
                        input.sourceVersion());
        if (data instanceof Branding value) assets.retain(value);
        return new View(saved.version(), data, null, null, null);
    }
}
