package com.ai.fabric.realapps.mcpserver.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "mcp_sandbox_service_state")
public class SandboxServiceState {

    @Id
    @Column(nullable = false, length = 220)
    private String id;

    @Column(name = "sandbox_id", nullable = false, length = 120)
    private String sandboxId;

    @Column(name = "service_name", nullable = false, length = 40)
    private String serviceName;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "current_version", nullable = false, length = 40)
    private String currentVersion;

    @Column(nullable = false)
    private int revision;

    @Column(name = "restart_count", nullable = false)
    private int restartCount;

    @Column(name = "last_restart_at")
    private Instant lastRestartAt;

    @Column(name = "last_touched_at", nullable = false)
    private Instant lastTouchedAt;

    @Version
    private long persistenceVersion;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public int getRestartCount() {
        return restartCount;
    }

    public void setRestartCount(int restartCount) {
        this.restartCount = restartCount;
    }

    public Instant getLastRestartAt() {
        return lastRestartAt;
    }

    public void setLastRestartAt(Instant lastRestartAt) {
        this.lastRestartAt = lastRestartAt;
    }

    public Instant getLastTouchedAt() {
        return lastTouchedAt;
    }

    public void setLastTouchedAt(Instant lastTouchedAt) {
        this.lastTouchedAt = lastTouchedAt;
    }
}
