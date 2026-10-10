package com.lumira.platform.web;

import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.common.ContentPolicyGuard;
import com.lumira.platform.common.ContentPolicyPort;
import com.lumira.platform.content.RichText;
import java.lang.reflect.Type;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One server-side boundary for authenticated JSON text submissions across modules. */
@ControllerAdvice
public class GlobalContentPolicyAdvice extends RequestBodyAdviceAdapter {
    private static final Set<String> TEXT_FIELDS =
            Set.of(
                    "name",
                    "displayName",
                    "realName",
                    "title",
                    "description",
                    "detailHtml",
                    "bodyHtml",
                    "body",
                    "content",
                    "adminReply",
                    "message",
                    "text",
                    "comment",
                    "remark",
                    "reason",
                    "label",
                    "defaultMessage",
                    "translations",
                    "values",
                    "value",
                    "region",
                    "labelDefault",
                    "changeReason",
                    "placeholder",
                    "groupLabel",
                    "options",
                    "websiteName",
                    "companyName",
                    "footerIcp",
                    "footerPoliceBeian",
                    "footerCopyright",
                    "userAgreementMarkdown",
                    "privacyAgreementMarkdown",
                    "textLines",
                    "personalizedTextLines",
                    "instructions",
                    "choices",
                    "scoreLevels",
                    "yesMeaning",
                    "noMeaning");

    private static boolean secret(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("credential")
                || lower.endsWith("key")
                || lower.equals("username");
    }

    private final ContentPolicyGuard guard;
    private final ObjectMapper mapper;

    public GlobalContentPolicyAdvice(
            org.springframework.beans.factory.ObjectProvider<ContentPolicyPort> policies,
            ObjectMapper mapper) {
        this.guard = new ContentPolicyGuard(policies);
        this.mapper = mapper;
    }

    @Override
    public boolean supports(
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        return !parameter.getContainingClass().isAnnotationPresent(ContentPolicyExempt.class)
                && !parameter.hasMethodAnnotation(ContentPolicyExempt.class);
    }

    @Override
    public Object afterBodyRead(
            Object body,
            HttpInputMessage message,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            return body;
        var tree = mapper.valueToTree(body);
        var source =
                parameter.getContainingClass().getSimpleName()
                        + "."
                        + parameter.getMethod().getName();
        inspect(tree, "", false, auth.getName(), source);
        return body;
    }

    private void inspect(
            JsonNode node, String path, boolean textField, String actor, String source) {
        if (node.isString()) {
            if (textField)
                guard.requireAllowed(
                        actor,
                        path.endsWith("Html")
                                ? RichText.visibleText(RichText.clean(node.asString()).html())
                                : node.asString(),
                        source + ":" + path);
        } else if (node.isArray()) {
            for (var child : node) inspect(child, path, textField, actor, source);
        } else if (node.isObject()) {
            for (var field : node.properties()) {
                String key = field.getKey();
                if (secret(key)) continue;
                inspect(
                        field.getValue(),
                        path.isEmpty() ? key : path + "." + key,
                        textField || TEXT_FIELDS.contains(key),
                        actor,
                        source);
            }
        }
    }
}
