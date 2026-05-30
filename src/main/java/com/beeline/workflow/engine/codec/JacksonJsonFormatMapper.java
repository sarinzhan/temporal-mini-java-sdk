package com.beeline.workflow.engine.codec;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Hibernate {@link FormatMapper} for {@code @JdbcTypeCode(SqlTypes.JSON)} columns, backed by the
 * application's Jackson 3 ({@code tools.jackson}) {@link ObjectMapper}.
 *
 * <p>Spring Boot 4 ships Jackson 3, but Hibernate 7's automatic JSON-mapper detection only finds
 * Jackson 2 ({@code com.fasterxml}) or Yasson — neither of which is on the classpath here. Without
 * an explicit mapper, every JSONB read/write fails at runtime ("Could not find a FormatMapper for
 * the JSON format"). Registering this one (see {@code WorkflowAutoConfiguration}) closes that gap.
 *
 * <p>All of the engine's JSON columns map to {@code String} fields that already contain serialized
 * JSON (the {@link PayloadCodec} owns the (de)serialization). For those we pass the text through
 * verbatim — re-encoding a JSON string through Jackson would double-quote and escape it, corrupting
 * the payload. Any non-String attribute falls back to normal Jackson (de)serialization.
 */
public final class JacksonJsonFormatMapper implements FormatMapper {

    private final ObjectMapper objectMapper;

    public JacksonJsonFormatMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType, WrapperOptions options) {
        if (javaType.getJavaTypeClass() == String.class) {
            return (T) charSequence.toString();
        }
        try {
            return objectMapper.readValue(charSequence.toString(),
                    objectMapper.constructType(javaType.getJavaType()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to read JSON into " + javaType.getJavaType(), e);
        }
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
        if (javaType.getJavaTypeClass() == String.class) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to write JSON from " + javaType.getJavaType(), e);
        }
    }
}
