/**
 * Copyright (C) 2015 Zalando SE (http://tech.zalando.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zalando.stups.fullstop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.zalando.stups.clients.kio.KioOperations;
import org.zalando.stups.fullstop.clients.pierone.PieroneOperations;
import org.zalando.stups.fullstop.teams.TeamOperations;
import org.zalando.stups.tokens.AccessTokens;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
// @ActiveProfiles("clientConfigTest")
public class ClientConfigTest {

    @Autowired(required = false)
    private KioOperations kioOperations;

    @Autowired(required = false)
    private PieroneOperations pieroneOperations;

    @Autowired(required = false)
    private TeamOperations teamOperations;

    @Test
    public void testKioOperations() throws Exception {
        assertThat(kioOperations).isNotNull();
    }

    @Test
    public void testPieroneOperations() throws Exception {
        assertThat(pieroneOperations).isNotNull();
    }

    @Test
    public void testTeamOperations() throws Exception {
        assertThat(teamOperations).isNotNull();
    }

    @Configuration
    @Import(ClientConfig.class)
    @PropertySource("classpath:config/application-ClientConfigTest.properties")
    static class TestConfig {

        @Bean AccessTokens accessTokens() {
            return mock(AccessTokens.class);
        }

        @Bean static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}