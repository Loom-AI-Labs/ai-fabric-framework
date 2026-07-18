package com.ai.fabric.realapps.chat.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceModeResolverTest {

    private final CommerceModeResolver resolver = new CommerceModeResolver();

    @Test
    void mapsPositionsToDefaultCommerceModes() {
        assertThat(resolver.resolve("landing", null).mode()).isEqualTo("navigator");
        assertThat(resolver.resolve("catalog", null).mode()).isEqualTo("navigator");
        assertThat(resolver.resolve("search", null).mode()).isEqualTo("navigator_deep");
        assertThat(resolver.resolve("product-detail", null).mode()).isEqualTo("cart_assistant");
        assertThat(resolver.resolve("cart", null).mode()).isEqualTo("cart_assistant");
        assertThat(resolver.resolve("checkout", null).mode()).isEqualTo("executor");
        assertThat(resolver.resolve("orders", null).mode()).isEqualTo("cart_assistant");
        assertThat(resolver.resolve("support", null).mode()).isEqualTo("navigator");
    }

    @Test
    void explicitModeWinsOverPositionDefault() {
        CommerceModeResolver.ResolvedRouting routing = resolver.resolve("cart", "navigator_deep");

        assertThat(routing.position()).isEqualTo("cart");
        assertThat(routing.mode()).isEqualTo("navigator_deep");
        assertThat(routing.modeSource()).isEqualTo("explicit");
    }

    @Test
    void unknownPositionFallsBackToLanding() {
        CommerceModeResolver.ResolvedRouting routing = resolver.resolve("somewhere", null);

        assertThat(routing.position()).isEqualTo("landing");
        assertThat(routing.mode()).isEqualTo("navigator");
        assertThat(routing.modeSource()).isEqualTo("position_default");
    }
}
