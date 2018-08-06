package org.zalando.stups.fullstop.plugin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zalando.stups.fullstop.plugin.config.RegionPluginProperties;
import org.zalando.stups.fullstop.violation.ViolationSink;

import java.util.List;
import java.util.function.Predicate;

import static java.util.Collections.singletonMap;
import static java.util.function.Predicate.isEqual;
import static org.zalando.stups.fullstop.violation.ViolationType.WRONG_REGION;

/**
 * @author gkneitschel
 */
@Component
public class RegionPlugin extends AbstractEC2InstancePlugin {

    private final ViolationSink violationSink;

    private final RegionPluginProperties regionPluginProperties;

    @Autowired
    public RegionPlugin(final EC2InstanceContextProvider contextProvider, final ViolationSink violationSink,
                        final RegionPluginProperties regionPluginProperties) {
        super(contextProvider);
        this.violationSink = violationSink;
        this.regionPluginProperties = regionPluginProperties;
    }

    @Override
    protected Predicate<? super String> supportsEventName() {
        // It should only be possible to change the region in "RunInstances" events
        // So activating the plugin while processing these event types should be sufficient
        return isEqual(RUN_INSTANCES);
    }

    @Override
    protected void process(final EC2InstanceContext ec2InstanceContext) {
        final List<String> allowedRegions = regionPluginProperties.getWhitelistedRegions();
        if (!allowedRegions.contains(ec2InstanceContext.getRegionAsString())) {
            violationSink.put(ec2InstanceContext.violation()
                    .withType(WRONG_REGION)
                    .withPluginFullyQualifiedClassName(RegionPlugin.class)
                    .withMetaInfo(singletonMap("allowed_regions", allowedRegions))
                    .build());
        }
    }
}
