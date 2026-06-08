package com.ai.fabric.runtime.admin;

public class RuntimeActionCatalogSourceProperties {

    private String type;
    private String path;
    private boolean optional;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isOptional() {
        return optional;
    }

    public void setOptional(boolean optional) {
        this.optional = optional;
    }
}
