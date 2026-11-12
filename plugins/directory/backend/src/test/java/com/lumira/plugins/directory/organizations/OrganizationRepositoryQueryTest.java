package com.lumira.plugins.directory.organizations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lumira.platform.common.PageRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class OrganizationRepositoryQueryTest {
    @Test
    void listPlacesColumnFiltersBeforeOrderAndPagination() {
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class);
        @SuppressWarnings("unchecked")
        var rows =
                (JdbcClient.MappedQuerySpec<OrganizationRepository.Organization>)
                        mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.params(anyMap())).thenReturn(statement);
        doReturn(rows).when(statement).query(any(RowMapper.class));
        when(rows.list()).thenReturn(List.of());

        var repository = new OrganizationRepository(jdbc);
        repository.list(
                new PageRequest(
                        1,
                        10,
                        "",
                        Map.of("code", "QA_ORG_20260928_181851_C"),
                        Map.of("code", "contains")),
                false,
                null,
                "admin");

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        var statementSql = sql.getValue();
        int order = statementSql.indexOf("ORDER BY sort_no ASC,code ASC,id ASC");
        assertTrue(
                statementSql.indexOf(
                                "strpos(lower(coalesce(CAST(code AS text), '')), lower(:list_filter_0)) > 0")
                        < order,
                statementSql);
        assertTrue(order < statementSql.indexOf("LIMIT :limit OFFSET :offset"), statementSql);
    }
}
