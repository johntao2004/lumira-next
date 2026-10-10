package com.lumira.platform.organizations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class OrganizationSortNoBindingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOmittedNullAndIntegerBoundaries() throws Exception {
        assertNull(mapper.readValue("{}", OrganizationService.Input.class).sortNo());
        assertNull(mapper.readValue("{\"sortNo\":null}", OrganizationService.Input.class).sortNo());
        assertEquals(
                0, mapper.readValue("{\"sortNo\":0}", OrganizationService.Input.class).sortNo());
        assertEquals(
                Integer.MAX_VALUE,
                mapper.readValue("{\"sortNo\":2147483647}", OrganizationService.Input.class)
                        .sortNo());
    }

    @Test
    void rejectsFractionalNegativeAndOutOfRangeValues() {
        assertThrows(
                JacksonException.class,
                () -> mapper.readValue("{\"sortNo\":1.5}", OrganizationService.Input.class));
        assertThrows(
                JacksonException.class,
                () -> mapper.readValue("{\"sortNo\":-1}", OrganizationService.Input.class));
        assertThrows(
                JacksonException.class,
                () -> mapper.readValue("{\"sortNo\":2147483648}", OrganizationService.Input.class));
    }
}
