package ai.fabric.provider.springai;

import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trusted request-parameter helper for attaching Spring AI advisors to a single AI Fabric
 * generation request.
 */
public final class SpringAiRequestAdvisorSupport {

    public static final String PARAM_ADVISORS_ENABLED = "__aiFabricSpringAiAdvisorsEnabled";
    public static final String PARAM_ADVISORS = "__aiFabricSpringAiAdvisors";

    private SpringAiRequestAdvisorSupport() {
    }

    public static Map<String, Object> requestParameters(Collection<? extends Advisor> advisors) {
        return requestParameters(Map.of(), advisors);
    }

    public static Map<String, Object> requestParameters(Map<String, Object> baseParameters,
                                                        Collection<? extends Advisor> advisors) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (baseParameters != null) {
            parameters.putAll(baseParameters);
        }
        List<Advisor> trustedAdvisors = advisorList(advisors);
        if (!trustedAdvisors.isEmpty()) {
            parameters.put(PARAM_ADVISORS_ENABLED, true);
            parameters.put(PARAM_ADVISORS, trustedAdvisors);
        }
        return Map.copyOf(parameters);
    }

    public static boolean isAdvisorBridgeEnabled(Map<String, Object> parameters) {
        return parameters != null && Boolean.TRUE.equals(parameters.get(PARAM_ADVISORS_ENABLED));
    }

    public static List<Advisor> advisorsFrom(Map<String, Object> parameters) {
        if (!isAdvisorBridgeEnabled(parameters)) {
            return List.of();
        }
        Object value = parameters.get(PARAM_ADVISORS);
        if (value instanceof Advisor advisor) {
            return List.of(advisor);
        }
        if (value instanceof Advisor[] advisors) {
            return advisorList(List.of(advisors));
        }
        if (value instanceof Collection<?> collection) {
            List<Advisor> advisors = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (item instanceof Advisor advisor) {
                    advisors.add(advisor);
                }
            }
            return advisors.isEmpty() ? List.of() : List.copyOf(advisors);
        }
        return List.of();
    }

    public static List<String> advisorNames(Collection<? extends Advisor> advisors) {
        if (advisors == null || advisors.isEmpty()) {
            return List.of();
        }
        return advisors.stream()
            .filter(advisor -> advisor != null && advisor.getName() != null && !advisor.getName().isBlank())
            .map(advisor -> advisor.getName().trim())
            .distinct()
            .toList();
    }

    private static List<Advisor> advisorList(Collection<? extends Advisor> advisors) {
        if (advisors == null || advisors.isEmpty()) {
            return List.of();
        }
        List<Advisor> trustedAdvisors = new ArrayList<>(advisors.size());
        for (Advisor advisor : advisors) {
            if (advisor != null) {
                trustedAdvisors.add(advisor);
            }
        }
        return trustedAdvisors.isEmpty() ? List.of() : List.copyOf(trustedAdvisors);
    }
}
