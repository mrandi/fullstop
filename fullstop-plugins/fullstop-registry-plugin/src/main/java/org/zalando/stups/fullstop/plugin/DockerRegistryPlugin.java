package org.zalando.stups.fullstop.plugin;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.zalando.stups.fullstop.violation.ViolationSink;
import org.zalando.stups.pierone.client.TagSummary;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.Collections.singletonMap;
import static java.util.function.Predicate.isEqual;
import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.slf4j.LoggerFactory.getLogger;
import static org.zalando.stups.fullstop.violation.ViolationType.ARTIFACT_BUILT_FROM_DIRTY_REPOSITORY;
import static org.zalando.stups.fullstop.violation.ViolationType.IMAGE_IN_PIERONE_NOT_FOUND;
import static org.zalando.stups.fullstop.violation.ViolationType.SCM_SOURCE_JSON_MISSING;
import static org.zalando.stups.fullstop.violation.ViolationType.SCM_URL_IS_MISSING_IN_SCM_SOURCE_JSON;

public class DockerRegistryPlugin extends AbstractEC2InstancePlugin {

    private final Logger log = getLogger(getClass());

    private final ViolationSink violationSink;

    public DockerRegistryPlugin(final EC2InstanceContextProvider contextProvider, final ViolationSink violationSink) {
        super(contextProvider);
        this.violationSink = violationSink;
    }

    @Override
    protected Predicate<? super String> supportsEventName() {
        return isEqual(RUN_INSTANCES).or(isEqual(START_INSTANCES));
    }

    @Override
    protected void process(final EC2InstanceContext context) {
        if (!context.getRuntime().filter(isEqual("Docker")).isPresent()) {
            log.info("Unknown or missing Taupage runtime for {}. Skip DockerRegistryPlugin", context);
            return;
        }

        final Optional<String> optionalSource = context.getSource();
        if (!optionalSource.isPresent()) {
            log.warn("Docker source is missing for {}. Skip DockerRegistryPlugin", context);
            return;
        }

        final String source = optionalSource.get();
        final Optional<TagSummary> pieroneTag = context.getPieroneTag();
        if (!pieroneTag.isPresent()) {
            violationSink.put(
                    context.violation()
                            .withType(IMAGE_IN_PIERONE_NOT_FOUND)
                            .withPluginFullyQualifiedClassName(DockerRegistryPlugin.class)
                            .withMetaInfo(singletonMap("source", source))
                            .build());
            return;
        }

        final Optional<Map<String, String>> optionalScmSource = context.getScmSource();
        if (!optionalScmSource.isPresent()) {
            violationSink.put(
                    context.violation()
                            .withType(SCM_SOURCE_JSON_MISSING)
                            .withPluginFullyQualifiedClassName(DockerRegistryPlugin.class)
                            .withMetaInfo(singletonMap("source", source))
                            .build());
            return;
        }

        final Map<String, String> scmSource = optionalScmSource.get();
        if (isBlank(scmSource.get("url"))) {
            violationSink.put(
                    context.violation()
                            .withType(SCM_URL_IS_MISSING_IN_SCM_SOURCE_JSON)
                            .withPluginFullyQualifiedClassName(DockerRegistryPlugin.class)
                            .withMetaInfo(singletonMap("source", source))
                            .build());
        }

        if (!isBlank(scmSource.get("status")) && !equalsIgnoreCase(scmSource.get("status"), "false")) {
            final ImmutableMap.Builder<String, String> metaInfo = ImmutableMap.builder();
            scmSource.forEach((key, value) -> metaInfo.put("scm_source_" + key, value));
            metaInfo.put("source", source);
            violationSink.put(
                    context.violation()
                            .withType(ARTIFACT_BUILT_FROM_DIRTY_REPOSITORY)
                            .withPluginFullyQualifiedClassName(DockerRegistryPlugin.class)
                            .withMetaInfo(metaInfo.build())
                            .build());
        }
    }
}
