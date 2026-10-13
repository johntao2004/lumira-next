package com.lumira.platform.ai;

import com.lumira.platform.ai.AiProviderProfile.ProviderKind;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PlatformOperation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class AiDecisionService {
    public enum QuestionType {
        CHOICE,
        SCORE,
        NOUL
    }

    public record JudgmentRequest(
            UUID providerId,
            String state,
            QuestionType type,
            String instructions,
            Map<String, String> choices,
            List<String> scoreLevels,
            String yesMeaning,
            String noMeaning) {}

    private final AiProviderService providers;
    private final ModelConnectionService connections;
    private final AdministrationPort guard;
    private final AuditPort audit;
    private final TransactionTemplate auditTransaction;

    @Autowired
    public AiDecisionService(
            AiProviderService providers,
            ModelConnectionService connections,
            AdministrationPort guard,
            AuditPort audit,
            PlatformTransactionManager manager) {
        this(providers, connections, guard, audit, new TransactionTemplate(manager));
    }

    AiDecisionService(
            AiProviderService providers,
            ModelConnectionService connections,
            AdministrationPort guard,
            AuditPort audit) {
        this(providers, connections, guard, audit, (TransactionTemplate) null);
    }

    private AiDecisionService(
            AiProviderService providers,
            ModelConnectionService connections,
            AdministrationPort guard,
            AuditPort audit,
            TransactionTemplate auditTransaction) {
        this.providers = providers;
        this.connections = connections;
        this.guard = guard;
        this.audit = audit;
        this.auditTransaction = auditTransaction;
    }

    public JsonNode judge(JudgmentRequest input, String actor) {
        guard.requireOperationGlobal(actor, PlatformOperation.AI_MODELS_TEST);
        if (input == null
                || input.providerId() == null
                || input.type() == null
                || input.state() == null
                || input.state().isBlank()
                || input.state().length() > 8000
                || input.instructions() == null
                || input.instructions().isBlank()
                || input.instructions().length() > 2000) invalid();
        AiProviderProfile provider = providers.requireEnabled(input.providerId(), actor);
        if (provider.providerKind() != ProviderKind.TYPESAFE_JEV
                || !provider.capabilityCodes().contains(input.type().name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "aiDecisionProviderRequired");
        }
        var question = new LinkedHashMap<String, Object>();
        question.put("type", input.type().name().toLowerCase(java.util.Locale.ROOT));
        question.put("instructions", input.instructions().strip());
        switch (input.type()) {
            case CHOICE -> {
                if (input.choices() == null
                        || input.choices().size() < 2
                        || input.choices().size() > 255
                        || input.choices().keySet().stream()
                                .anyMatch(
                                        key -> key == null || !key.matches("[a-zA-Z0-9_.-]{1,80}"))
                        || input.choices().values().stream()
                                .anyMatch(
                                        value ->
                                                value == null
                                                        || value.isBlank()
                                                        || value.length() > 500)) invalid();
                question.put("criteria", input.choices());
            }
            case SCORE -> {
                if (input.scoreLevels() == null
                        || input.scoreLevels().size() < 2
                        || input.scoreLevels().size() > 10
                        || input.scoreLevels().stream()
                                .anyMatch(
                                        value ->
                                                value == null
                                                        || value.isBlank()
                                                        || value.length() > 300)) invalid();
                question.put("criteria", input.scoreLevels());
            }
            case NOUL -> {
                if (input.yesMeaning() != null && input.yesMeaning().length() > 500
                        || input.noMeaning() != null && input.noMeaning().length() > 500) invalid();
                var criteria = new LinkedHashMap<String, Object>();
                if (input.yesMeaning() != null && !input.yesMeaning().isBlank())
                    criteria.put("true", input.yesMeaning());
                if (input.noMeaning() != null && !input.noMeaning().isBlank())
                    criteria.put("false", input.noMeaning());
                if (!criteria.isEmpty()) question.put("criteria", criteria);
            }
        }
        JsonNode response =
                connections.judge(provider, input.state().strip(), Map.of("decision", question));
        if (auditTransaction == null) {
            audit.append(actor, "ai.judgment.executed", provider.id());
        } else {
            auditTransaction.executeWithoutResult(
                    status -> audit.append(actor, "ai.judgment.executed", provider.id()));
        }
        return response;
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalidAiJudgment");
    }
}
