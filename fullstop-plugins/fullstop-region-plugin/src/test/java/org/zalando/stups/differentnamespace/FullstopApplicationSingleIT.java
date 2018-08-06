package org.zalando.stups.differentnamespace;

import com.amazonaws.services.cloudtrail.processinglibrary.model.CloudTrailEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.plugin.core.PluginRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.zalando.stups.fullstop.plugin.FullstopPlugin;
import org.zalando.stups.fullstop.plugin.RegionPlugin;
import org.zalando.stups.fullstop.plugin.config.RegionPluginProperties;
import org.zalando.stups.fullstop.violation.ViolationSink;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.zalando.stups.fullstop.events.TestCloudTrailEventSerializer.createCloudTrailEvent;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = FullstopApplication.class, properties = "debug=true")
@ActiveProfiles("single")
public class FullstopApplicationSingleIT {

    @Autowired
    private PluginRegistry<FullstopPlugin, CloudTrailEvent> pluginRegistry;

    @Autowired
    private RegionPlugin regionPlugin;

    @Autowired
    private RegionPluginProperties regionPluginProperties;

    @Autowired
    private ViolationSink violationSink;

    @Test
    public void testRegionPlugin() {

        assertThat(regionPluginProperties.getWhitelistedRegions()).containsOnly("us-west-1");

        final List<FullstopPlugin> plugins = pluginRegistry.getPlugins();
        assertThat(plugins).isNotEmpty();
        assertThat(plugins).contains(regionPlugin);

        final CloudTrailEvent cloudTrailEvent = createCloudTrailEvent("/run-instance-us-west.json");

        for (final FullstopPlugin plugin : plugins) {
            plugin.processEvent(cloudTrailEvent);
        }

        assertThat(((CountingViolationSink) violationSink).getInvocationCount()).isEqualTo(0);
    }

    @Test
    public void testRegionPluginThatShouldReportViolations() {

        assertThat(regionPluginProperties.getWhitelistedRegions()).containsOnly("us-west-1");

        final List<FullstopPlugin> plugins = pluginRegistry.getPlugins();
        assertThat(plugins).isNotEmpty();
        assertThat(plugins).contains(regionPlugin);

        final CloudTrailEvent cloudTrailEvent = createCloudTrailEvent("/run-instance-eu-central.json");

        for (final FullstopPlugin plugin : plugins) {
            plugin.processEvent(cloudTrailEvent);
        }

        assertThat(((CountingViolationSink) violationSink).getInvocationCount()).isGreaterThan(0);
    }
}
