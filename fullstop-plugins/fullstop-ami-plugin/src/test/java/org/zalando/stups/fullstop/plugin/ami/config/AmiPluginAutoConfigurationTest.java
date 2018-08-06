package org.zalando.stups.fullstop.plugin.ami.config;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;
import org.zalando.stups.fullstop.plugin.EC2InstanceContextProvider;
import org.zalando.stups.fullstop.plugin.ami.AmiPlugin;
import org.zalando.stups.fullstop.violation.ViolationSink;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;


@RunWith(SpringRunner.class)
@SpringBootTest
public class AmiPluginAutoConfigurationTest {

    @Autowired(required = false)
    private AmiPlugin amiPlugin;

    @Test
    public void testAmiPlugin() throws Exception {
        assertThat(amiPlugin).isNotNull();
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        EC2InstanceContextProvider contextProvieder() {
            return mock(EC2InstanceContextProvider.class);
        }

        @Bean
        ViolationSink violationSink() {
            return mock(ViolationSink.class);
        }
    }
}
