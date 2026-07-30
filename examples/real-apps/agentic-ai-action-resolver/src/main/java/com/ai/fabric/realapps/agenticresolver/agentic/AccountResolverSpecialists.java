package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.specialist.SpecialistId;

public final class AccountResolverSpecialists {

    public static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("account-resolver", "1");
    public static final SpecialistId READ_SPECIALIST_ID =
        SpecialistId.of("account-resolver-read", "1");
    public static final SpecialistId BILLING_ADVISOR_SPECIALIST_ID =
        SpecialistId.of("billing-resolution-advisor", "1");
    public static final SpecialistId SUPPORT_CREDIT_SPECIALIST_ID =
        SpecialistId.of("support-credit-proposer", "1");
    public static final SpecialistId DELEGATION_COORDINATOR_ID =
        SpecialistId.of("account-resolution-coordinator", "1");
    public static final SpecialistId HANDOFF_INTAKE_ID =
        SpecialistId.of("account-resolution-intake", "1");
    public static final SpecialistId CONVERSATION_MANAGER_ID =
        SpecialistId.of("account-conversation-manager", "1");
    public static final SpecialistId MANAGER_BILLING_ADVISOR_ID =
        SpecialistId.of("billing-resolution-manager-advisor", "1");
    public static final SpecialistId MANAGER_READ_SPECIALIST_ID =
        SpecialistId.of("account-resolver-manager-read", "1");
    public static final String PROFILE_ACTION = "get_account_profile";
    public static final String BILLING_ASSESSMENT_ACTION =
        "assess_billing_resolution";
    public static final String UPDATE_ADDRESS_ACTION = "update_address";
    public static final String REQUEST_REFUND_ACTION = "request_refund";
    public static final String POLICY_VECTOR_SPACE =
        "account-resolution-policy";

    private AccountResolverSpecialists() {}
}
