package com.lumira.plugins.directory.organizations;

import java.math.BigInteger;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Accepts only JSON integer tokens in the nonnegative Java int range. */
public final class OrganizationSortNoDeserializer extends ValueDeserializer<Integer> {
    private static final BigInteger MAXIMUM = BigInteger.valueOf(Integer.MAX_VALUE);

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(
                    Integer.class, "sortNo must be a JSON integer between 0 and 2147483647");
        }

        BigInteger value = parser.getBigIntegerValue();
        if (value.signum() < 0 || value.compareTo(MAXIMUM) > 0) {
            return context.reportInputMismatch(
                    Integer.class, "sortNo must be a JSON integer between 0 and 2147483647");
        }
        return value.intValue();
    }
}
