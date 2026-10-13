package com.lumira.platform.dictionaries;

import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageResult;
import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileRegistrationLimits;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DictionaryLookupRepository {
    public PageResult<ProfileDictionaryPort.EnabledDictionary> listEnabled(PageRequest page) {
        var items =
                jdbc.sql(
                                "SELECT code,name,enabled FROM platform_dictionary WHERE enabled AND (:search='' OR strpos(lower(code),lower(:search))>0 OR strpos(lower(name),lower(:search))>0) ORDER BY code,id LIMIT :limit OFFSET :offset")
                        .param("search", page.search())
                        .param("limit", page.pageSize())
                        .param("offset", page.offset())
                        .query(ProfileDictionaryPort.EnabledDictionary.class)
                        .list();
        long total =
                jdbc.sql(
                                "SELECT count(*) FROM platform_dictionary WHERE enabled AND (:search='' OR strpos(lower(code),lower(:search))>0 OR strpos(lower(name),lower(:search))>0)")
                        .param("search", page.search())
                        .query(Long.class)
                        .single();
        return new PageResult<>(items, total, page.page(), page.pageSize());
    }

    /**
     * Read complete enabled policy trees in one database snapshot, including ancestor filtering.
     */
    public List<DictionaryLookupPort.PolicyItem> policyItems(Collection<String> codes) {
        if (codes.isEmpty()) return List.of();
        return jdbc.sql(
                        """
            WITH RECURSIVE available AS (
              SELECT d.code AS dictionary_code,i.* FROM platform_dictionary d
              JOIN platform_dictionary_item i ON i.dictionary_id=d.id
              WHERE d.code IN (:codes) AND d.enabled AND i.enabled AND i.parent_id IS NULL
              UNION ALL
              SELECT a.dictionary_code,i.* FROM platform_dictionary_item i
              JOIN available a ON i.dictionary_id=a.dictionary_id AND i.parent_id=a.id WHERE i.enabled
            ) SELECT dictionary_code,value,label,remark,sort_order FROM available
              ORDER BY dictionary_code,sort_order,value,id
            """)
                .param("codes", Set.copyOf(codes))
                .query(DictionaryLookupPort.PolicyItem.class)
                .list();
    }

    /**
     * Read option ids and labels for a whole projected profile schema in one query. Disabled
     * dictionaries, items, and descendants of disabled items are excluded at every depth.
     */
    public Map<String, Long> enabledProfileOptionCounts(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return Map.of();
        var requested =
                codes.stream()
                        .filter(Objects::nonNull)
                        .filter(code -> !code.isBlank())
                        .distinct()
                        .toList();
        if (requested.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Long>();
        requested.forEach(code -> result.put(code, 0L));
        jdbc.sql(
                        """
                    WITH RECURSIVE available AS (
                      SELECT d.code AS dictionary_code,i.id,i.parent_id,i.dictionary_id
                      FROM platform_dictionary d JOIN platform_dictionary_item i ON i.dictionary_id=d.id
                      WHERE d.code IN (:codes) AND d.enabled AND i.enabled AND i.parent_id IS NULL
                      UNION ALL
                      SELECT a.dictionary_code,i.id,i.parent_id,i.dictionary_id
                      FROM platform_dictionary_item i JOIN available a
                        ON i.dictionary_id=a.dictionary_id AND i.parent_id=a.id
                      WHERE i.enabled
                    )
                    SELECT dictionary_code,count(*) AS option_count FROM available GROUP BY dictionary_code
                    """)
                .param("codes", Set.copyOf(requested))
                .query(
                        (rs, row) ->
                                Map.entry(
                                        rs.getString("dictionary_code"),
                                        rs.getLong("option_count")))
                .list()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    public Map<String, List<ProfileDictionaryPort.PublicOption>> enabledProfileOptions(
            Collection<String> codes, int perDictionaryLimit) {
        if (perDictionaryLimit < 1
                || perDictionaryLimit > ProfileRegistrationLimits.OPTION_QUERY_LIMIT)
            throw new IllegalArgumentException("perDictionaryLimit");
        if (codes == null || codes.isEmpty()) return Map.of();
        var requested =
                codes.stream()
                        .filter(Objects::nonNull)
                        .filter(code -> !code.isBlank())
                        .distinct()
                        .toList();
        if (requested.isEmpty()) return Map.of();
        var rows =
                jdbc.sql(
                                """
                    WITH RECURSIVE available AS (
                      SELECT d.code AS dictionary_code,i.id,i.parent_id,i.dictionary_id,i.sort_order,i.value,i.label
                      FROM platform_dictionary d JOIN platform_dictionary_item i ON i.dictionary_id=d.id
                      WHERE d.code IN (:codes) AND d.enabled AND i.enabled AND i.parent_id IS NULL
                      UNION ALL
                      SELECT a.dictionary_code,i.id,i.parent_id,i.dictionary_id,i.sort_order,i.value,i.label
                      FROM platform_dictionary_item i JOIN available a
                        ON i.dictionary_id=a.dictionary_id AND i.parent_id=a.id
                      WHERE i.enabled
                    ), ranked AS (
                      SELECT dictionary_code,id,value,label,sort_order,
                             row_number() OVER (PARTITION BY dictionary_code ORDER BY sort_order,value,id) AS option_rank
                      FROM available
                    )
                    SELECT dictionary_code,id,value,label FROM ranked
                    WHERE option_rank<=:limit
                    ORDER BY dictionary_code,sort_order,value,id
                    """)
                        .param("codes", Set.copyOf(requested))
                        .param("limit", perDictionaryLimit)
                        .query(
                                (rs, row) ->
                                        Map.entry(
                                                rs.getString("dictionary_code"),
                                                new ProfileDictionaryPort.PublicOption(
                                                        rs.getObject("id", UUID.class).toString(),
                                                        rs.getString("label"))))
                        .list();
        var grouped = new LinkedHashMap<String, List<ProfileDictionaryPort.PublicOption>>();
        for (var row : rows)
            grouped.computeIfAbsent(row.getKey(), ignored -> new ArrayList<>()).add(row.getValue());
        grouped.replaceAll((code, values) -> List.copyOf(values));
        return Map.copyOf(grouped);
    }

    public record Option(UUID id, UUID parentId, String value, String label, int sortOrder) {}

    private final tools.jackson.databind.ObjectMapper mapper;
    private final JdbcClient jdbc;
    // Build the enabled tree before search/pagination so disabled ancestors cannot leak
    // descendants.
    private static final String ENABLED =
            "WITH RECURSIVE available AS (SELECT i.* FROM platform_dictionary_item i WHERE i.dictionary_id=:dictionary AND i.parent_id IS NULL AND i.enabled UNION ALL SELECT i.* FROM platform_dictionary_item i JOIN available a ON i.parent_id=a.id WHERE i.dictionary_id=:dictionary AND i.enabled) ";
    private static final String FILTER =
            " WHERE (:search='' OR strpos(lower(value),lower(:search))>0 OR strpos(lower(label),lower(:search))>0)";

    public DictionaryLookupRepository(JdbcClient jdbc, tools.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Walk only selected ancestor chains, once per unique dictionary/item pair. */
    public Set<ProfileDictionaryPort.Selection> enabledSelections(
            Collection<ProfileDictionaryPort.Selection> requested) {
        return selectionMetadata(requested).entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Unchanged selections may be disabled, but must still have an intact root chain. */
    public Set<ProfileDictionaryPort.Selection> existingSelections(
            Collection<ProfileDictionaryPort.Selection> requested) {
        return selectionMetadata(requested).keySet();
    }

    /**
     * Resolve stored profile selections even when disabled, without exposing them through lookup.
     */
    public Map<ProfileDictionaryPort.Selection, ProfileDictionaryPort.SelectionMetadata>
            selectionMetadata(Collection<ProfileDictionaryPort.Selection> requested) {
        if (requested.isEmpty()) return Map.of();
        String sql =
                """
            WITH RECURSIVE requested AS (
              SELECT DISTINCT code,id FROM jsonb_to_recordset(CAST(:requested AS jsonb)) AS r(code text,id uuid)
            ), paths AS (
              SELECT r.code,r.id AS selected,i.id,i.parent_id,i.dictionary_id,i.enabled,ARRAY[i.id] AS visited
              FROM requested r JOIN platform_dictionary d ON d.code=r.code
              JOIN platform_dictionary_item i ON i.dictionary_id=d.id AND i.id=r.id
              UNION ALL
              SELECT a.code,a.selected,p.id,p.parent_id,p.dictionary_id,p.enabled,a.visited||p.id
              FROM paths a JOIN platform_dictionary_item p ON p.id=a.parent_id AND p.dictionary_id=a.dictionary_id
              WHERE NOT p.id=ANY(a.visited)
            ), valid AS (
              SELECT code,selected,dictionary_id,bool_and(enabled) AS path_enabled,bool_or(parent_id IS NULL) AS rooted
              FROM paths GROUP BY code,selected,dictionary_id
            ) SELECT v.code,v.selected,item.label,(dictionary.enabled AND v.path_enabled) AS enabled
              FROM valid v JOIN platform_dictionary dictionary ON dictionary.id=v.dictionary_id AND dictionary.code=v.code
              JOIN platform_dictionary_item item ON item.dictionary_id=v.dictionary_id AND item.id=v.selected
              WHERE v.rooted
            """;
        return jdbc
                .sql(sql)
                .param("requested", mapper.writeValueAsString(requested))
                .query(
                        (rs, n) ->
                                Map.entry(
                                        new ProfileDictionaryPort.Selection(
                                                rs.getString("code"),
                                                rs.getObject("selected", UUID.class)),
                                        new ProfileDictionaryPort.SelectionMetadata(
                                                rs.getString("label"), rs.getBoolean("enabled"))))
                .list()
                .stream()
                .collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, Map.Entry::getValue));
    }

    public UUID dictionary(String code) {
        return jdbc.sql("SELECT id FROM platform_dictionary WHERE code=:code AND enabled")
                .param("code", code)
                .query(UUID.class)
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "dictionaryUnavailable"));
    }

    public boolean isEnabled(String code) {
        return jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM platform_dictionary WHERE code=:code AND enabled)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    private Option row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Option(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("value"),
                rs.getString("label"),
                rs.getInt("sort_order"));
    }

    public long count(UUID dictionary, PageRequest p) {
        return jdbc.sql(ENABLED + "SELECT count(*) FROM available" + FILTER)
                .param("dictionary", dictionary)
                .param("search", p.search())
                .query(Long.class)
                .single();
    }

    public List<Option> list(UUID dictionary, PageRequest p) {
        return jdbc.sql(
                        ENABLED
                                + "SELECT * FROM available"
                                + FILTER
                                + " ORDER BY sort_order,value,id LIMIT :limit OFFSET :offset")
                .param("dictionary", dictionary)
                .param("search", p.search())
                .param("limit", p.pageSize())
                .param("offset", p.offset())
                .query((rs, n) -> row(rs))
                .list();
    }

    public Option find(UUID dictionary, UUID id) {
        return jdbc.sql(ENABLED + "SELECT * FROM available WHERE id=:id")
                .param("dictionary", dictionary)
                .param("id", id)
                .query((rs, n) -> row(rs))
                .optional()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "dictionaryUnavailable"));
    }
}
