package com.ai.fabric.examples.smoke.health;

import java.util.Map;

/** Adds application-owned, safe readiness details to the shared demo health response. */
@FunctionalInterface
public interface DemoHealthContributor {

    Map<String, Object> details();
}
