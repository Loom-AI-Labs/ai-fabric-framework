package ai.fabric.provider.springai;

import io.micrometer.observation.ObservationRegistry;

final class SpringAiObservationRegistration {

    SpringAiObservationRegistration(ObservationRegistry observationRegistry,
                                    SpringAiObservationHandler observationHandler) {
        if (observationRegistry != null && observationRegistry != ObservationRegistry.NOOP) {
            observationRegistry.observationConfig().observationHandler(observationHandler);
        }
    }
}
