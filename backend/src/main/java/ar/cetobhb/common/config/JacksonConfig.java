package ar.cetobhb.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;

/**
 * Strict request parsing for the public API. Jackson 3 and Boot 4 default to lenient unknown properties, last-wins
 * duplicate keys and string/float-to-number coercion, so each setting is made explicit here (verification U-1, U-5).
 * The read constraints bound hostile documents far below the Jackson defaults (depth 500, strings of 100 MB).
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictJsonMapper() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .withCoercionConfigDefaults(config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }

    @Bean
    JsonFactoryBuilderCustomizer boundedJsonFactory() {
        return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(4096)
                .maxNestingDepth(8)
                .maxNumberLength(20)
                .maxNameLength(64)
                .maxTokenCount(4096)
                .build());
    }
}
