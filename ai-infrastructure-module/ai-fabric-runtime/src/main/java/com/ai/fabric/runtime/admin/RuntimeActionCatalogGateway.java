package com.ai.fabric.runtime.admin;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RuntimeActionCatalogGateway {

    private final Binder binder;

    public RuntimeActionCatalogGateway(Environment environment) {
        this.binder = Binder.get(environment);
    }

    public List<RuntimeActionCatalogSourceProperties> getSources() {
        return binder.bind("ai.actions.sources", Bindable.listOf(RuntimeActionCatalogSourceProperties.class))
            .map(List::copyOf)
            .orElse(List.of());
    }
}
