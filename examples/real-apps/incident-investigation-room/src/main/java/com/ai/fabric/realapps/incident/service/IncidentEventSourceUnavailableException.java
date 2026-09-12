package com.ai.fabric.realapps.incident.service;

public final class IncidentEventSourceUnavailableException
    extends RuntimeException {

    public IncidentEventSourceUnavailableException(String sourceName) {
        super("The approved incident source is unavailable: " + sourceName);
    }
}
