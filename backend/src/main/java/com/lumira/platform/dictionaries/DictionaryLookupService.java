package com.lumira.platform.dictionaries;

import com.lumira.platform.common.*;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DictionaryLookupService implements ProfileDictionaryPort, DictionaryLookupPort {
    private final DictionaryLookupRepository repository;

    public DictionaryLookupService(DictionaryLookupRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public PageResult<ProfileDictionaryPort.EnabledDictionary> listEnabled(PageRequest page) {
        return repository.listEnabled(page);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public java.util.Map<String, Long> enabledOptionCounts(java.util.Collection<String> codes) {
        return repository.enabledProfileOptionCounts(codes);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public java.util.Map<String, java.util.List<ProfileDictionaryPort.PublicOption>> enabledOptions(
            java.util.Collection<String> codes, int perDictionaryLimit) {
        return repository.enabledProfileOptions(codes, perDictionaryLimit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public java.util.Set<ProfileDictionaryPort.Selection> enabledSelections(
            java.util.Collection<ProfileDictionaryPort.Selection> requested) {
        return repository.enabledSelections(requested);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public java.util.Set<ProfileDictionaryPort.Selection> existingSelections(
            java.util.Collection<ProfileDictionaryPort.Selection> requested) {
        return repository.existingSelections(requested);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public java.util.Map<ProfileDictionaryPort.Selection, ProfileDictionaryPort.SelectionMetadata>
            selectionMetadata(java.util.Collection<ProfileDictionaryPort.Selection> requested) {
        return repository.selectionMetadata(requested);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Override
    public java.util.List<DictionaryLookupPort.PolicyItem> policyItems(
            java.util.Collection<String> codes) {
        return repository.policyItems(codes);
    }

    @Transactional(readOnly = true)
    @Override
    public boolean isEnabled(String code) {
        return code != null && repository.isEnabled(code);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<DictionaryLookupRepository.Option> list(String code, PageRequest p) {
        var id = repository.dictionary(code);
        return new PageResult<>(
                repository.list(id, p), repository.count(id, p), p.page(), p.pageSize());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DictionaryLookupRepository.Option find(String code, UUID id) {
        return repository.find(repository.dictionary(code), id);
    }
}
