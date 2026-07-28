package ai.fabric.intent.orchestration.capability;

/**
 * Resolves server-authoritative capabilities from requested and trusted constraints.
 */
public interface EffectiveCapabilitiesResolver {

    EffectiveCapabilityProfile resolve(CapabilityResolutionRequest request);
}
