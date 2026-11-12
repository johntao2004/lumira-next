package com.lumira.platform.web;

import com.lumira.platform.common.ContentPolicyExempt;
import com.lumira.platform.common.ContentPolicyGuard;
import com.lumira.platform.common.ContentPolicyPort;
import com.lumira.platform.content.RichText;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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

    private static final ConcurrentHashMap<String, Boolean> SECRET_CACHE =
            new ConcurrentHashMap<>(128);

    static {
        for (String field : TEXT_FIELDS) {
            SECRET_CACHE.put(field, false);
        }
        SECRET_CACHE.put("password", true);
        SECRET_CACHE.put("secret", true);
        SECRET_CACHE.put("token", true);
        SECRET_CACHE.put("credential", true);
        SECRET_CACHE.put("apiKey", true);
        SECRET_CACHE.put("appKey", true);
        SECRET_CACHE.put("accessKey", true);
        SECRET_CACHE.put("secretKey", true);
        SECRET_CACHE.put("privateKey", true);
        SECRET_CACHE.put("publicKey", true);
        SECRET_CACHE.put("messageKey", true);
        SECRET_CACHE.put("username", true);
        SECRET_CACHE.put("key", true);

        SECRET_CACHE.put("id", false);
        SECRET_CACHE.put("data", false);
        SECRET_CACHE.put("type", false);
        SECRET_CACHE.put("status", false);
        SECRET_CACHE.put("code", false);
        SECRET_CACHE.put("baseUrl", false);
        SECRET_CACHE.put("url", false);
        SECRET_CACHE.put("email", false);
        SECRET_CACHE.put("phone", false);
        SECRET_CACHE.put("role", false);
        SECRET_CACHE.put("bio", false);
        SECRET_CACHE.put("messages", false);
    }

    private static boolean secret(String key) {
        if (key == null || key.length() < 3) return false;
        Boolean cached = SECRET_CACHE.get(key);
        if (cached != null) return cached;
        boolean result = computeSecret(key);
        if (SECRET_CACHE.size() < 2048) {
            SECRET_CACHE.put(key, result);
        }
        return result;
    }

    private static boolean computeSecret(String key) {
        return key.equalsIgnoreCase("username")
                || (key.length() >= 3 && key.regionMatches(true, key.length() - 3, "key", 0, 3))
                || containsIgnoreCase(key, "password")
                || containsIgnoreCase(key, "secret")
                || containsIgnoreCase(key, "token")
                || containsIgnoreCase(key, "credential");
    }

    private static boolean containsIgnoreCase(String src, String target) {
        final int targetLen = target.length();
        final int max = src.length() - targetLen;
        for (int i = 0; i <= max; i++) {
            if (src.regionMatches(true, i, target, 0, targetLen)) {
                return true;
            }
        }
        return false;
    }

    private record TextCheck(String text, String source) {}

    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<Method, String>>
            SOURCE_PREFIX_CACHE = new ConcurrentHashMap<>();

    private static String resolveSourcePrefix(MethodParameter parameter) {
        Method method = parameter.getMethod();
        Class<?> clazz = parameter.getContainingClass();
        if (method == null) {
            return clazz.getSimpleName() + ".unknown:";
        }
        ConcurrentHashMap<Method, String> methodMap = SOURCE_PREFIX_CACHE.get(clazz);
        if (methodMap != null) {
            String cached = methodMap.get(method);
            if (cached != null) return cached;
        }
        String prefix = clazz.getSimpleName() + "." + method.getName() + ":";
        SOURCE_PREFIX_CACHE
                .computeIfAbsent(clazz, c -> new ConcurrentHashMap<>(4))
                .put(method, prefix);
        return prefix;
    }

    private static final class RecordField {
        final String name;
        final Method accessor;
        final boolean isTextField;
        final boolean isHtml;
        final boolean isString;

        RecordField(
                String name,
                Method accessor,
                boolean isTextField,
                boolean isHtml,
                boolean isString) {
            this.name = name;
            this.accessor = accessor;
            this.isTextField = isTextField;
            this.isHtml = isHtml;
            this.isString = isString;
        }
    }

    private static final RecordField[] EMPTY_FIELDS = new RecordField[0];

    private static final class RecordMetadata {
        final boolean fallbackToJackson;
        final RecordField[] fields;

        RecordMetadata(boolean fallbackToJackson, RecordField[] fields) {
            this.fallbackToJackson = fallbackToJackson;
            this.fields = fields != null ? fields : EMPTY_FIELDS;
        }
    }

    private static final ConcurrentHashMap<Class<?>, RecordMetadata> RECORD_METADATA_CACHE =
            new ConcurrentHashMap<>();

    private static boolean isJacksonAnnotation(Annotation ann) {
        if (ann == null) return false;
        String name = ann.annotationType().getName();
        return name.contains("jackson") || name.endsWith("JsonProperty");
    }

    private static boolean hasJacksonAnnotation(AnnotatedElement element) {
        if (element == null) return false;
        for (Annotation ann : element.getAnnotations()) {
            if (isJacksonAnnotation(ann)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJacksonAnnotations(Class<?> clazz) {
        if (hasJacksonAnnotation(clazz)) return true;
        for (var field : clazz.getDeclaredFields()) {
            if (hasJacksonAnnotation(field)) return true;
        }
        for (var method : clazz.getDeclaredMethods()) {
            if (hasJacksonAnnotation(method)) return true;
        }
        for (var constructor : clazz.getDeclaredConstructors()) {
            for (var paramAnns : constructor.getParameterAnnotations()) {
                for (var ann : paramAnns) {
                    if (isJacksonAnnotation(ann)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSkippableType(Class<?> type) {
        return type.isPrimitive()
                || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)
                || Enum.class.isAssignableFrom(type)
                || java.time.temporal.Temporal.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || java.util.UUID.class.isAssignableFrom(type)
                || java.net.URI.class.isAssignableFrom(type)
                || java.net.URL.class.isAssignableFrom(type)
                || (type.isArray() && type.getComponentType().isPrimitive());
    }

    private static RecordMetadata inspectRecord(Class<?> clazz) {
        if (hasJacksonAnnotations(clazz)) {
            return new RecordMetadata(true, null);
        }
        RecordComponent[] components = clazz.getRecordComponents();
        if (components == null) {
            return new RecordMetadata(true, null);
        }
        List<RecordField> activeFields = new ArrayList<>(components.length);
        for (RecordComponent c : components) {
            if (hasJacksonAnnotation(c)) {
                return new RecordMetadata(true, null);
            }
            Method accessor = c.getAccessor();
            if (hasJacksonAnnotation(accessor) || !accessor.trySetAccessible()) {
                return new RecordMetadata(true, null);
            }
            String name = c.getName();
            if (computeSecret(name)) {
                continue;
            }
            Class<?> type = c.getType();
            if (isSkippableType(type)) {
                continue;
            }
            boolean isTextField = TEXT_FIELDS.contains(name);
            boolean isHtml = name.endsWith("Html");
            boolean isString = CharSequence.class.isAssignableFrom(type);
            activeFields.add(new RecordField(name, accessor, isTextField, isHtml, isString));
        }
        return new RecordMetadata(false, activeFields.toArray(RecordField[]::new));
    }

    private static RecordMetadata getRecordMetadata(Class<?> clazz) {
        return RECORD_METADATA_CACHE.computeIfAbsent(
                clazz, GlobalContentPolicyAdvice::inspectRecord);
    }

    private static boolean isScalarOrPrimitiveArray(Object val) {
        return val instanceof Number
                || val instanceof Boolean
                || val instanceof Character
                || val instanceof Enum<?>
                || val instanceof java.time.temporal.Temporal
                || val instanceof java.util.Date
                || val instanceof java.util.UUID
                || val instanceof java.net.URI
                || val instanceof java.net.URL
                || (val.getClass().isArray() && !(val instanceof Object[]));
    }

    private final ContentPolicyGuard guard;
    private final ObjectMapper mapper;
    private final org.springframework.transaction.support.TransactionTemplate readOnlyTransactions;

    public GlobalContentPolicyAdvice(
            org.springframework.beans.factory.ObjectProvider<ContentPolicyPort> policies,
            ObjectMapper mapper) {
        this(policies, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GlobalContentPolicyAdvice(
            org.springframework.beans.factory.ObjectProvider<ContentPolicyPort> policies,
            ObjectMapper mapper,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.guard = new ContentPolicyGuard(policies);
        this.mapper = mapper;
        if (transactions == null) {
            this.readOnlyTransactions = null;
        } else {
            var template =
                    new org.springframework.transaction.support.TransactionTemplate(transactions);
            template.setReadOnly(true);
            this.readOnlyTransactions = template;
        }
    }

    @Override
    public boolean supports(
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        return !parameter.getContainingClass().isAnnotationPresent(ContentPolicyExempt.class)
                && !parameter.hasMethodAnnotation(ContentPolicyExempt.class)
                && !parameter.hasParameterAnnotation(ContentPolicyExempt.class);
    }

    @Override
    public Object afterBodyRead(
            Object body,
            HttpInputMessage message,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        if (body == null) return null;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())
                || "anonymousUser".equals(auth.getName())
                || auth instanceof AnonymousAuthenticationToken) {
            return body;
        }

        String sourcePrefix = resolveSourcePrefix(parameter);
        var checks = new ArrayList<TextCheck>(4);
        collect(body, "", false, sourcePrefix, checks, 0);
        if (checks.isEmpty()) return body;

        String actor = auth.getName();
        if (readOnlyTransactions != null) {
            readOnlyTransactions.executeWithoutResult(
                    status -> {
                        for (var check : checks) {
                            guard.requireAllowed(actor, check.text(), check.source());
                        }
                    });
        } else {
            for (var check : checks) {
                guard.requireAllowed(actor, check.text(), check.source());
            }
        }
        return body;
    }

    private static void addTextCheck(
            CharSequence seq, boolean isHtml, String source, List<TextCheck> checks) {
        if (seq == null) return;
        String raw = seq.toString();
        if (raw.isBlank()) return;
        String text = isHtml ? RichText.visibleText(RichText.clean(raw).html()) : raw;
        if (text != null && !text.isBlank()) {
            checks.add(new TextCheck(text, source));
        }
    }

    private void collect(
            Object val,
            String path,
            boolean textField,
            String sourcePrefix,
            List<TextCheck> checks,
            int depth) {
        if (val == null || depth > 64) return;

        if (val instanceof CharSequence seq) {
            if (textField) {
                addTextCheck(seq, path.endsWith("Html"), sourcePrefix + path, checks);
            }
            return;
        }

        if (isScalarOrPrimitiveArray(val)) return;

        if (val instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object entryKey = entry.getKey();
                if (entryKey == null) continue;
                String key = entryKey.toString();
                if (secret(key)) continue;
                Object child = entry.getValue();
                if (child == null || isScalarOrPrimitiveArray(child)) continue;

                boolean nextTextField = textField || TEXT_FIELDS.contains(key);
                if (child instanceof CharSequence seq) {
                    if (nextTextField) {
                        String source =
                                path.isEmpty()
                                        ? sourcePrefix + key
                                        : sourcePrefix + path + "." + key;
                        addTextCheck(seq, key.endsWith("Html"), source, checks);
                    }
                    continue;
                }

                String nextPath = path.isEmpty() ? key : path + "." + key;
                collect(child, nextPath, nextTextField, sourcePrefix, checks, depth + 1);
            }
            return;
        }

        if (val instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                collectElement(child, path, textField, sourcePrefix, checks, depth);
            }
            return;
        }

        if (val instanceof Object[] array) {
            for (Object child : array) {
                collectElement(child, path, textField, sourcePrefix, checks, depth);
            }
            return;
        }

        if (val instanceof JsonNode node) {
            collectJsonNode(node, path, textField, sourcePrefix, checks);
            return;
        }

        Class<?> clazz = val.getClass();
        if (clazz.isRecord()) {
            RecordMetadata meta = getRecordMetadata(clazz);
            if (meta.fallbackToJackson) {
                collectJsonNode(mapper.valueToTree(val), path, textField, sourcePrefix, checks);
                return;
            }
            int startCheckCount = checks.size();
            try {
                for (RecordField field : meta.fields) {
                    Object child = field.accessor.invoke(val);
                    if (child == null || isScalarOrPrimitiveArray(child)) continue;

                    boolean nextTextField = textField || field.isTextField;
                    if (field.isString) {
                        if (nextTextField) {
                            String source =
                                    path.isEmpty()
                                            ? sourcePrefix + field.name
                                            : sourcePrefix + path + "." + field.name;
                            addTextCheck((CharSequence) child, field.isHtml, source, checks);
                        }
                        continue;
                    }

                    String nextPath = path.isEmpty() ? field.name : path + "." + field.name;
                    collect(child, nextPath, nextTextField, sourcePrefix, checks, depth + 1);
                }
            } catch (Throwable t) {
                while (checks.size() > startCheckCount) {
                    checks.remove(checks.size() - 1);
                }
                RECORD_METADATA_CACHE.put(clazz, new RecordMetadata(true, null));
                collectJsonNode(mapper.valueToTree(val), path, textField, sourcePrefix, checks);
            }
            return;
        }

        // Fallback for custom POJOs
        collectJsonNode(mapper.valueToTree(val), path, textField, sourcePrefix, checks);
    }

    private void collectElement(
            Object child,
            String path,
            boolean textField,
            String sourcePrefix,
            List<TextCheck> checks,
            int depth) {
        if (child == null || isScalarOrPrimitiveArray(child)) return;
        if (child instanceof CharSequence seq) {
            if (textField) {
                addTextCheck(seq, path.endsWith("Html"), sourcePrefix + path, checks);
            }
            return;
        }
        collect(child, path, textField, sourcePrefix, checks, depth + 1);
    }

    private void collectJsonNode(
            JsonNode node,
            String path,
            boolean textField,
            String sourcePrefix,
            List<TextCheck> checks) {
        if (node == null || node.isNull()) return;
        if (node.isString()) {
            if (textField) {
                addTextCheck(node.asString(), path.endsWith("Html"), sourcePrefix + path, checks);
            }
        } else if (node.isArray()) {
            for (var child : node) {
                collectJsonNode(child, path, textField, sourcePrefix, checks);
            }
        } else if (node.isObject()) {
            for (var field : node.properties()) {
                String key = field.getKey();
                if (secret(key)) continue;
                String nextPath = path.isEmpty() ? key : path + "." + key;
                collectJsonNode(
                        field.getValue(),
                        nextPath,
                        textField || TEXT_FIELDS.contains(key),
                        sourcePrefix,
                        checks);
            }
        }
    }
}
