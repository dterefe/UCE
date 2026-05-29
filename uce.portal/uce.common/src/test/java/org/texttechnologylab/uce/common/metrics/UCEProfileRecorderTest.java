package org.texttechnologylab.uce.common.metrics;

import junit.framework.TestCase;

public class UCEProfileRecorderTest extends TestCase {
    public void testDisabledScopeIsCloseable() {
        System.clearProperty(UCEProfileRecorder.ENABLED_PROPERTY);

        try (UCEProfileRecorder.Scope ignored = UCEProfileRecorder.scope("test-disabled")) {
            assertNotNull(ignored);
        }
    }

    public void testEnabledScopeIsCloseable() {
        System.setProperty(UCEProfileRecorder.ENABLED_PROPERTY, "true");
        System.setProperty(UCEProfileRecorder.SINK_PROPERTY, "none");

        try (UCEProfileRecorder.Scope ignored = UCEProfileRecorder.scope("test-enabled", "harness")) {
            assertNotNull(ignored);
        } finally {
            System.clearProperty(UCEProfileRecorder.ENABLED_PROPERTY);
            System.clearProperty(UCEProfileRecorder.SINK_PROPERTY);
        }
    }
}
