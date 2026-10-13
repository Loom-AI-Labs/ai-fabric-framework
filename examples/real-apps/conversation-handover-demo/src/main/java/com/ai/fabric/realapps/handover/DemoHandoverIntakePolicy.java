package com.ai.fabric.realapps.handover;

import ai.fabric.chat.control.HandoverAdmissionOutcome;
import ai.fabric.chat.control.HandoverAdmissionResult;
import ai.fabric.chat.control.HandoverIntakeField;
import ai.fabric.chat.control.HandoverIntakeFieldType;
import ai.fabric.chat.control.HandoverIntakeSchema;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
class DemoHandoverIntakePolicy {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^[+()0-9 .-]{6,40}$");

    private final HandoverIntakeSchema schema = new HandoverIntakeSchema(
        "DEMO_HANDOVER_INTAKE_V1",
        false,
        List.of(
            new HandoverIntakeField(
                "email",
                "Email",
                HandoverIntakeFieldType.EMAIL,
                true,
                254,
                "Required so the operator can preserve contact continuity for this handover.",
                true,
                List.of()
            ),
            new HandoverIntakeField(
                "name",
                "Name",
                HandoverIntakeFieldType.TEXT,
                false,
                120,
                "Optional display name for the operator.",
                false,
                List.of()
            ),
            new HandoverIntakeField(
                "phone",
                "Phone",
                HandoverIntakeFieldType.PHONE,
                false,
                40,
                "Optional phone follow-up after the live conversation.",
                true,
                List.of()
            )
        )
    );

    HandoverIntakeSchema schema() {
        return schema;
    }

    HandoverAdmissionResult intakeRequired() {
        return new HandoverAdmissionResult(
            HandoverAdmissionOutcome.HANDOVER_INTAKE_REQUIRED,
            schema,
            "HANDOVER_INTAKE_REQUIRED",
            "Enter a valid email address and consent to share it before the human handover starts. Name and phone are optional."
        );
    }

    ValidatedIntake validate(Map<String, String> rawIntake, List<String> rawConsentedFields) {
        Map<String, String> intake = rawIntake == null ? Map.of() : rawIntake;
        Set<String> consentedFields = rawConsentedFields == null
            ? Set.of()
            : rawConsentedFields.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Set<String> allowed = Set.of("email", "name", "phone");
        boolean hasUnknownField = intake.keySet().stream()
            .anyMatch(key -> key == null || !allowed.contains(key.trim().toLowerCase(Locale.ROOT)));
        if (hasUnknownField) {
            throw invalid("Handover intake contains an unsupported field.");
        }

        String email = value(intake, "email", 254);
        if (!StringUtils.hasText(email) || !EMAIL.matcher(email).matches()) {
            throw invalid("A valid email address is required for this demo handover.");
        }
        if (!consentedFields.contains("email")) {
            throw invalid("Consent is required to share the email address with the operator.");
        }

        String name = value(intake, "name", 120);
        String phone = value(intake, "phone", 40);
        if (StringUtils.hasText(phone)) {
            if (!PHONE.matcher(phone).matches()) {
                throw invalid("Phone must be a valid bounded phone number.");
            }
            if (!consentedFields.contains("phone")) {
                throw invalid("Consent is required to share the phone number with the operator.");
            }
        }
        return new ValidatedIntake(email, name, phone);
    }

    private String value(Map<String, String> intake, String key, int maximum) {
        String value = intake.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(key))
            .map(Map.Entry::getValue)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .findFirst()
            .orElse(null);
        if (value != null && value.length() > maximum) {
            throw invalid(key + " exceeds the maximum allowed length.");
        }
        return value;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    record ValidatedIntake(String email, String name, String phone) {}
}
