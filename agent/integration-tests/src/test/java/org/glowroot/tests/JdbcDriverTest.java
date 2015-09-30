/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.tests;

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.TransactionMarker;
import org.glowroot.agent.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class JdbcDriverTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldNotTriggerMockJdbcDriverToLoad() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries.get(0).message()).isEqualTo("major version");
    }

    public static class ShouldGenerateTraceWithNestedEntries
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            new MockDriver().getMajorVersion();
        }
    }
}