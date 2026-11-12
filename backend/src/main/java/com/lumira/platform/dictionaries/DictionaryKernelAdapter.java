package com.lumira.platform.dictionaries;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.common.PluginServicesPort;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import com.lumira.platform.dictionaries.api.DictionaryReadServicePort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Host compatibility boundary. Each real lookup leases the current dictionary provider. */
@Component
public class DictionaryKernelAdapter implements ProfileDictionaryPort, DictionaryLookupPort {
    private final ObjectProvider<PluginServicesPort> plugins;

    public DictionaryKernelAdapter(ObjectProvider<PluginServicesPort> plugins) {
        this.plugins = plugins;
    }

    private <T> T read(Function<DictionaryReadServicePort, T> call) {
        return plugins.getObject().service("dictionaries", DictionaryReadServicePort.class, call);
    }

    @Override
    public PageResult<EnabledDictionary> listEnabled(PageRequest page) {
        return read(provider -> provider.listEnabled(page));
    }

    @Override
    public PageResult<Item> lookupItems(String code, PageRequest page) {
        return read(provider -> provider.lookupItems(code, page));
    }

    @Override
    public Item lookupItem(String code, UUID id) {
        return read(provider -> provider.lookupItem(code, id));
    }

    @Override
    public Map<String, Long> enabledOptionCounts(Collection<String> codes) {
        return codes.isEmpty() ? Map.of() : read(provider -> provider.enabledOptionCounts(codes));
    }

    @Override
    public Map<String, List<PublicOption>> enabledOptions(Collection<String> codes, int limit) {
        return codes.isEmpty() ? Map.of() : read(provider -> provider.enabledOptions(codes, limit));
    }

    @Override
    public boolean isEnabled(String code) {
        return code != null && !code.isBlank() && read(provider -> provider.isEnabled(code));
    }

    @Override
    public Set<Selection> enabledSelections(Collection<Selection> requested) {
        return requested.isEmpty()
                ? Set.of()
                : read(provider -> provider.enabledSelections(requested));
    }

    @Override
    public Set<Selection> existingSelections(Collection<Selection> requested) {
        return requested.isEmpty()
                ? Set.of()
                : read(provider -> provider.existingSelections(requested));
    }

    @Override
    public Map<Selection, SelectionMetadata> selectionMetadata(Collection<Selection> requested) {
        return requested.isEmpty()
                ? Map.of()
                : read(provider -> provider.selectionMetadata(requested));
    }

    @Override
    public List<PolicyItem> policyItems(Collection<String> codes) {
        return codes.isEmpty() ? List.of() : read(provider -> provider.policyItems(codes));
    }
}
