package com.lumira.platform.directory;

import com.lumira.platform.common.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Kernel callers lease the current directory business provider for one synchronous operation. */
@Component
public class DirectoryBusinessAdapter
        implements AiUserDirectoryPort, TemplateUserDataPort, RoleAssignmentPreviewPort {
    private final ObjectProvider<PluginServicesPort> plugins;

    public DirectoryBusinessAdapter(ObjectProvider<PluginServicesPort> plugins) {
        this.plugins = plugins;
    }

    private <T extends PluginServicePort, R> R call(Class<T> contract, Function<T, R> action) {
        return plugins.getObject().service("directory", contract, action);
    }

    @Override
    public PageResult<UserView> list(PageRequest request, String actor) {
        return call(DirectoryAiServicePort.class, provider -> provider.list(request, actor));
    }

    @Override
    public UserView find(UUID id, String actor) {
        return call(DirectoryAiServicePort.class, provider -> provider.find(id, actor));
    }

    @Override
    public UserView findForOperation(UUID id, String actor, PlatformOperation operation) {
        return call(
                DirectoryAiServicePort.class,
                provider -> provider.findForOperation(id, actor, operation));
    }

    @Override
    public UserView create(CreateUser input, String actor) {
        return call(DirectoryAiServicePort.class, provider -> provider.create(input, actor));
    }

    @Override
    public UserView update(UUID id, UpdateUser input, String actor) {
        return call(DirectoryAiServicePort.class, provider -> provider.update(id, input, actor));
    }

    @Override
    public void delete(UUID id, long version, String actor) {
        call(
                DirectoryAiServicePort.class,
                provider -> {
                    provider.delete(id, version, actor);
                    return null;
                });
    }

    @Override
    public void requirePreviewable(String actor, UUID userId, Set<UUID> roleIds) {
        call(
                DirectoryRoleAssignmentServicePort.class,
                provider -> {
                    provider.requirePreviewable(actor, userId, roleIds);
                    return null;
                });
    }

    @Override
    public List<Field> readableFields(String actor) {
        return call(DirectoryTemplateServicePort.class, provider -> provider.readableFields(actor));
    }

    @Override
    public List<Field> importableFields(String actor) {
        return call(
                DirectoryTemplateServicePort.class, provider -> provider.importableFields(actor));
    }

    @Override
    public Map<String, String> readValues(UUID userId, String actor) {
        return call(
                DirectoryTemplateServicePort.class, provider -> provider.readValues(userId, actor));
    }

    @Override
    public Map<UUID, Map<String, String>> readValuesBatch(List<UUID> userIds, String actor) {
        return call(
                DirectoryTemplateServicePort.class,
                provider -> provider.readValuesBatch(userIds, actor));
    }

    @Override
    public Map<UUID, Map<String, String>> readValuesBatch(
            List<UUID> userIds, String actor, Set<String> requestedFields) {
        return call(
                DirectoryTemplateServicePort.class,
                provider -> provider.readValuesBatch(userIds, actor, requestedFields));
    }
}
