package com.lumira.platform.identity;

import com.lumira.platform.common.CaptchaProofPort;
import com.lumira.platform.common.ClientAddressResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Fails closed when no policy/proof provider is present; it never invents a non-CAPTCHA policy. */
@Component
public class CaptchaProofAccess {
    public record Gate(
            CaptchaProofPort port,
            CaptchaProofPort.Action action,
            CaptchaProofPort.Requirement requirement) {}

    private final ObjectProvider<CaptchaProofPort> providers;
    private final ClientAddressResolver addresses;
    private final PublicAuthCrypto crypto;

    public CaptchaProofAccess(
            ObjectProvider<CaptchaProofPort> providers,
            ClientAddressResolver addresses,
            PublicAuthCrypto crypto) {
        this.providers = providers;
        this.addresses = addresses;
        this.crypto = crypto;
    }

    public boolean providerAvailable() {
        return providers.getIfAvailable() != null;
    }

    public boolean actionAvailable(
            CaptchaProofPort.Action action, UUID accountId, UUID defaultRoleId) {
        var port = providers.getIfAvailable();
        return port != null && port.readyFor(action, accountId, defaultRoleId);
    }

    public boolean actionsAvailable(
            java.util.Set<CaptchaProofPort.Action> actions, UUID defaultRoleId) {
        if (actions == null || actions.isEmpty()) return providerAvailable();
        return actions.stream().allMatch(action -> actionAvailable(action, null, defaultRoleId));
    }

    public Gate begin(CaptchaProofPort.Action action, UUID accountId, UUID defaultRoleId) {
        return begin(action, accountId, defaultRoleId, null);
    }

    public Gate begin(
            CaptchaProofPort.Action action,
            UUID accountId,
            UUID defaultRoleId,
            HttpServletRequest request) {
        CaptchaProofPort port = providers.getIfAvailable();
        if (port == null) throw unavailable();
        var requestFacts = CaptchaRequestFacts.from(request, addresses, crypto, false);
        var requirement = port.requirement(action, accountId, defaultRoleId, requestFacts);
        if (requirement.required() && !port.readyForRequiredPolicy()) throw unavailable();
        return new Gate(port, action, requirement);
    }

    /**
     * Consume supplied proofs for optional as well as required actions to prevent cross-action
     * reuse.
     */
    public void consume(
            Gate gate, String proofId, HttpServletRequest request, String expectedAttemptId) {
        boolean supplied = proofId != null && !proofId.isBlank();
        if (gate.requirement().required() && !supplied)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "captchaRequired");
        if (supplied)
            gate.port()
                    .consume(
                            proofId,
                            gate.action(),
                            gate.requirement().policyRevision(),
                            CaptchaRequestFacts.from(request, addresses, crypto, false),
                            expectedAttemptId);
    }

    public void preflight(
            Gate gate, String proofId, HttpServletRequest request, String expectedAttemptId) {
        boolean supplied = proofId != null && !proofId.isBlank();
        if (gate.requirement().required() && !supplied)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "captchaRequired");
        if (supplied)
            gate.port()
                    .preflight(
                            proofId,
                            gate.action(),
                            gate.requirement().policyRevision(),
                            CaptchaRequestFacts.from(request, addresses, crypto, false),
                            expectedAttemptId);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "captchaUnavailable");
    }
}
