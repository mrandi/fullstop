package org.zalando.stups.differentnamespace;

import com.amazonaws.services.cloudtrail.processinglibrary.model.CloudTrailEvent;
import org.assertj.core.api.Assertions;
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

import java.util.List;

import static org.zalando.stups.fullstop.events.TestCloudTrailEventSerializer.createCloudTrailEvent;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = FullstopApplication.class, properties = "debug=true")
@ActiveProfiles("triple")
public class FullstopApplicationTripleIT {

    @Autowired
    private PluginRegistry<FullstopPlugin, CloudTrailEvent> pluginRegistry;

    @Autowired
    private RegionPlugin regionPlugin;

    @Autowired
    private RegionPluginProperties regionPluginProperties;

    @Test
    public void testRegionPlugin() {

        Assertions.assertThat(regionPluginProperties.getWhitelistedRegions()).containsOnly(
                "us-west-1", "us-east-1",
                "us-west-2");

        final List<FullstopPlugin> plugins = pluginRegistry.getPlugins();
        Assertions.assertThat(plugins).isNotEmpty();
        Assertions.assertThat(plugins).contains(regionPlugin);

        final CloudTrailEvent cloudTrailEvent = createCloudTrailEvent("/run-instance-us-west.json");

        for (final FullstopPlugin plugin : plugins) {
            plugin.processEvent(cloudTrailEvent);
        }
    }
}
