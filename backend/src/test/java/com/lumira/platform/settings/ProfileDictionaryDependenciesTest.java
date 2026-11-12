package com.lumira.platform.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.identity.api.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileDictionaryDependenciesTest {
    @Test
    void currentReferencesBlockOnlyTheirProviderAndDisappearOnlyAfterDetachment() {
        var schemas = mock(ProfileSchemaPort.class);
        var references = new ProfileDictionaryDependencies(schemas);
        assertTrue(references.blockers("notifications").isEmpty());
        verifyNoInteractions(schemas);
        var field = mock(ProfileFields.Field.class);
        when(field.usesDictionary()).thenReturn(true);
        when(schemas.read())
                .thenReturn(
                        new ProfileSchemaPort.Snapshot(1, new ProfileFields(List.of(field))),
                        new ProfileSchemaPort.Snapshot(2, new ProfileFields(List.of())));
        assertEquals(List.of("dictionaryReferenced:settings"), references.blockers("dictionaries"));
        assertTrue(references.blockers("dictionaries").isEmpty());
        verify(schemas, times(2)).read();
    }

    @Test
    void unavailableReferenceStorageCannotBeInterpretedAsNoDependencies() {
        var schemas = mock(ProfileSchemaPort.class);
        when(schemas.read()).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(
                IllegalStateException.class,
                () -> new ProfileDictionaryDependencies(schemas).blockers("dictionaries"));
    }
}
