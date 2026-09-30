package com.lumira.platform.dictionaries;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.identity.api.ProfileDictionaryPort;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LUMIRA_TEST_DB_URL", matches = ".+")
class DictionaryLookupRepositoryTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DictionaryLookupRepository repository;
    private String schema;
    private UUID dictionaryId;
    private UUID rootId;
    private UUID itemId;
    private ProfileDictionaryPort.Selection selection;

    @BeforeEach
    void setUp() {
        String url = System.getenv("LUMIRA_TEST_DB_URL");
        String user = System.getenv("LUMIRA_TEST_DB_USER");
        String password = System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_dictionary_lookup_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);

        var dataSource =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        jdbc = new JdbcTemplate(dataSource);
        repository =
                new DictionaryLookupRepository(JdbcClient.create(dataSource), new JsonMapper());
        jdbc.execute(
                "CREATE TABLE platform_dictionary (id UUID PRIMARY KEY, code TEXT UNIQUE NOT NULL, enabled BOOLEAN NOT NULL)");
        jdbc.execute(
                "CREATE TABLE platform_dictionary_item (id UUID PRIMARY KEY, dictionary_id UUID NOT NULL, parent_id UUID, value TEXT NOT NULL, label TEXT NOT NULL, sort_order INTEGER NOT NULL, enabled BOOLEAN NOT NULL)");

        dictionaryId = UUID.randomUUID();
        rootId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        selection = new ProfileDictionaryPort.Selection("qa_choices", itemId);
        jdbc.update(
                "INSERT INTO platform_dictionary(id,code,enabled) VALUES(?, 'qa_choices', true)",
                dictionaryId);
        jdbc.update(
                "INSERT INTO platform_dictionary_item(id,dictionary_id,parent_id,value,label,sort_order,enabled) VALUES(?,?,NULL,'ROOT','Root',0,true)",
                rootId,
                dictionaryId);
        jdbc.update(
                "INSERT INTO platform_dictionary_item(id,dictionary_id,parent_id,value,label,sort_order,enabled) VALUES(?,?,?,?,?,1,true)",
                itemId,
                dictionaryId,
                rootId,
                "ITEM",
                "Choice");
    }

    @AfterEach
    void cleanUp() {
        if (admin != null && schema != null)
            admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void unchangedDisabledItemIsRetainableButStaysOutOfGenericLookup() {
        jdbc.update("UPDATE platform_dictionary_item SET enabled=false WHERE id=?", itemId);

        assertEquals(Set.of(), repository.enabledSelections(List.of(selection)));
        assertEquals(Set.of(selection), repository.existingSelections(List.of(selection)));
        assertEquals(
                new ProfileDictionaryPort.SelectionMetadata("Choice", false),
                repository.selectionMetadata(List.of(selection)).get(selection));
        assertEquals(
                1,
                repository.count(
                        dictionaryId, new com.lumira.platform.common.PageRequest(1, 20, "")));
        assertThrows(ResponseStatusException.class, () -> repository.find(dictionaryId, itemId));
    }

    @Test
    void disabledAncestorsAndDictionariesDoNotInvalidateRetainedValuesButRejectNewSelection() {
        jdbc.update("UPDATE platform_dictionary_item SET enabled=false WHERE id=?", rootId);
        assertEquals(Set.of(selection), repository.existingSelections(List.of(selection)));
        assertEquals(Set.of(), repository.enabledSelections(List.of(selection)));
        assertEquals(
                new ProfileDictionaryPort.SelectionMetadata("Choice", false),
                repository.selectionMetadata(List.of(selection)).get(selection));

        jdbc.update("UPDATE platform_dictionary_item SET enabled=true WHERE id=?", rootId);
        assertEquals(Set.of(selection), repository.existingSelections(List.of(selection)));
        assertEquals(Set.of(selection), repository.enabledSelections(List.of(selection)));
        jdbc.update("UPDATE platform_dictionary SET enabled=false WHERE id=?", dictionaryId);
        assertEquals(Set.of(selection), repository.existingSelections(List.of(selection)));
        assertEquals(Set.of(), repository.enabledSelections(List.of(selection)));
        assertEquals(
                new ProfileDictionaryPort.SelectionMetadata("Choice", false),
                repository.selectionMetadata(List.of(selection)).get(selection));
    }

    @Test
    void storedUuidMustStillBelongToTheCurrentDictionaryCode() {
        jdbc.update(
                "INSERT INTO platform_dictionary(id,code,enabled) VALUES(?, 'qa_replacement', true)",
                UUID.randomUUID());
        var changedSchemaSelection = new ProfileDictionaryPort.Selection("qa_replacement", itemId);

        assertEquals(Set.of(), repository.existingSelections(List.of(changedSchemaSelection)));
        assertEquals(Set.of(), repository.enabledSelections(List.of(changedSchemaSelection)));
        assertEquals(
                java.util.Map.of(), repository.selectionMetadata(List.of(changedSchemaSelection)));
    }
}
