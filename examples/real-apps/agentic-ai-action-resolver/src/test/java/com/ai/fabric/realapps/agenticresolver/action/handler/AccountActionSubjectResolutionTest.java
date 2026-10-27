package com.ai.fabric.realapps.agenticresolver.action.handler;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import com.ai.fabric.realapps.agenticresolver.config.AgenticResolverTrustBoundary;
import com.ai.fabric.realapps.agenticresolver.entity.Address;
import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.ai.fabric.realapps.agenticresolver.service.SubscriptionService;
import com.ai.fabric.realapps.agenticresolver.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountActionSubjectResolutionTest {

    private final AccountResolutionService accounts = mock(AccountResolutionService.class);
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);
    private final UserService users = mock(UserService.class);
    private final BaseActionHandler base = new BaseActionHandler(users) {};
    private final GetAccountProfileActionHandler profile = new GetAccountProfileActionHandler(accounts, users);
    private final UUID accountId = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(value = ExecutionSource.class, names = {"INTERACTIVE", "APPLICATION"})
    void readsProfileAndGroundingFactsFromTrustedAccountNotOpaqueActor(ExecutionSource source) {
        AccountResolutionService.AccountProfile account = accountProfile();
        when(accounts.accountProfile(accountId)).thenReturn(account);
        String actorId = "demo:agentic-resolver-" + UUID.randomUUID() + ":missing-payment";
        ActionContext context = spy(context(source, actorId,
            new ExecutionSubjectRef("account", accountId.toString()),
            AgenticResolverTrustBoundary.TENANT_ID, AgenticResolverTrustBoundary.DEPLOYMENT_ID));

        // Exercise opaque caller identity independently of framework accessor projection.
        doReturn(actorId).when(context).userId();
        assertThat(context.userId()).isEqualTo(actorId);
        assertThat(profile.allowed(context)).isTrue();
        ActionResult result = profile.execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().toMap()).containsEntry("accountProfile", account);
        assertThat(profile.facts(result, context))
            .containsEntry("paymentMethodPresent", false)
            .containsEntry("billingAddressValidated", true);
        verifyNoInteractions(users, subscriptions);
    }

    @Test
    void accountSubjectWinsOverUuidActorAndUntrustedTargetParameters() {
        UUID actorId = UUID.randomUUID();
        ActionContext context = context(ExecutionSource.INTERACTIVE, actorId.toString(),
            new ExecutionSubjectRef("account", accountId.toString()),
            AgenticResolverTrustBoundary.TENANT_ID, AgenticResolverTrustBoundary.DEPLOYMENT_ID)
            .withActionParams(Map.of("userId", actorId.toString(), "accountId", actorId.toString()));

        assertThat(base.requireCurrentUser(context)).isEqualTo(accountId);
        verifyNoInteractions(users, accounts, subscriptions);
    }

    @Test
    void allAccountWritePermissionChecksUseTheSameTrustedSubject() {
        ActionContext context = validContext();
        when(subscriptions.hasActiveSubscription(accountId)).thenReturn(true);

        assertThat(new UpdateAddressActionHandler(subscriptions, users).allowed(context)).isTrue();
        assertThat(new UpdatePaymentMethodActionHandler(accounts, subscriptions, users).allowed(context)).isTrue();
        assertThat(new RequestRefundActionHandler(accounts, subscriptions, users).allowed(context)).isTrue();
        assertThat(new CancelSubscriptionActionHandler(subscriptions, users).allowed(context)).isTrue();
        assertThat(new UpgradeSubscriptionActionHandler(subscriptions, users).allowed(context)).isTrue();
        assertThat(new DowngradeSubscriptionActionHandler(subscriptions, users).allowed(context)).isTrue();
        assertThat(new SubscribeActionHandler(subscriptions, users).allowed(context)).isFalse();

        when(subscriptions.hasActiveSubscription(accountId)).thenReturn(false);
        assertThat(new SubscribeActionHandler(subscriptions, users).allowed(context)).isTrue();
        verifyNoInteractions(users, accounts);
    }

    @Test
    void addressWriteTargetsOnlyTheTrustedAccountsSubscription() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder().id(subscriptionId).userId(accountId).build();
        when(subscriptions.getActiveSubscription(accountId)).thenReturn(Optional.of(subscription));
        UpdateAddressActionHandler handler = new UpdateAddressActionHandler(subscriptions, users);

        ActionResult result = handler.execute("BILLING", "101 Market St", "San Francisco", "CA", "94105", "USA",
            validContext());

        assertThat(result.isSuccess()).isTrue();
        verify(subscriptions).updateAddress(eq(subscriptionId), eq(Address.AddressType.BILLING), any());
        verifyNoInteractions(users, accounts);
    }

    @Test
    void invalidTrustedBoundaryNeverFallsBackToAParseableActorOrParameters() {
        List<ActionContext> invalid = List.of(
            context(ExecutionSource.INTERACTIVE, "92", null,
                AgenticResolverTrustBoundary.TENANT_ID, AgenticResolverTrustBoundary.DEPLOYMENT_ID),
            context(ExecutionSource.INTERACTIVE, "92", new ExecutionSubjectRef("subscription", accountId.toString()),
                AgenticResolverTrustBoundary.TENANT_ID, AgenticResolverTrustBoundary.DEPLOYMENT_ID),
            context(ExecutionSource.INTERACTIVE, "92", new ExecutionSubjectRef("account", accountId.toString()),
                "another-tenant", AgenticResolverTrustBoundary.DEPLOYMENT_ID),
            context(ExecutionSource.INTERACTIVE, "92", new ExecutionSubjectRef("account", accountId.toString()),
                AgenticResolverTrustBoundary.TENANT_ID, "another-deployment"),
            context(ExecutionSource.INTERACTIVE, "92", new ExecutionSubjectRef("account", accountId.toString()),
                null, AgenticResolverTrustBoundary.DEPLOYMENT_ID),
            context(ExecutionSource.INTERACTIVE, "92", new ExecutionSubjectRef("account", accountId.toString()),
                AgenticResolverTrustBoundary.TENANT_ID, null),
            context(ExecutionSource.INTERACTIVE, "92", new ExecutionSubjectRef("account", "92"),
                AgenticResolverTrustBoundary.TENANT_ID, AgenticResolverTrustBoundary.DEPLOYMENT_ID),
            context(ExecutionSource.INTERACTIVE, "92", new ExecutionSubjectRef("account", "not-an-account-uuid"),
                AgenticResolverTrustBoundary.TENANT_ID, AgenticResolverTrustBoundary.DEPLOYMENT_ID)
        );
        for (ActionContext context : invalid) {
            ActionContext withTarget = context.withActionParams(Map.of("userId", accountId.toString()));
            assertThatThrownBy(() -> base.requireCurrentUser(withTarget)).isInstanceOf(IllegalArgumentException.class);
            assertThat(profile.allowed(withTarget)).isFalse();
            assertThat(new UpdateAddressActionHandler(subscriptions, users).allowed(withTarget)).isFalse();
        }
        verifyNoInteractions(users, accounts, subscriptions);
    }

    @Test
    void missingContextIsDeniedWithoutAccessingAccountState() {
        assertThatThrownBy(() -> base.requireCurrentUser(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(profile.allowed(null)).isFalse();
        verifyNoInteractions(users, accounts, subscriptions);
    }

    private ActionContext validContext() {
        return context(ExecutionSource.INTERACTIVE, "demo:agentic-resolver-" + UUID.randomUUID() + ":missing-address",
            new ExecutionSubjectRef("account", accountId.toString()),
            AgenticResolverTrustBoundary.TENANT_ID, AgenticResolverTrustBoundary.DEPLOYMENT_ID);
    }

    private ActionContext context(ExecutionSource source, String actorId, ExecutionSubjectRef subject,
                                  String tenantId, String deploymentId) {
        TrustedExecutionContext trusted = new TrustedExecutionContext(
            new ExecutionPrincipal(actorId, source == ExecutionSource.INTERACTIVE
                ? ExecutionPrincipalType.END_USER : ExecutionPrincipalType.SERVICE),
            subject, source, tenantId, deploymentId, Set.of("action:get_account_profile", "action:update_address"),
            null, Instant.now());
        OrchestrationContext orchestration = OrchestrationContext.builder()
            .userId(actorId).sessionId("actor-subject-regression").build();
        OrchestrationRequest request = new OrchestrationRequest("Inspect my account without changing anything.",
            orchestration, trusted, ConversationPersistencePolicy.NEVER, null, null, null,
            OrchestrationRequestPurpose.SPECIALIST);
        return new ActionContext(orchestration, PipelineContext.from(request));
    }

    private AccountResolutionService.AccountProfile accountProfile() {
        return new AccountResolutionService.AccountProfile(
            new AccountResolutionService.AccountHolderProfile("Demo Account", false, null),
            new AccountResolutionService.SubscriptionProfile("ACTIVE", true, "MONTHLY", null, null, null, null),
            new AccountResolutionService.PaymentMethodProfile(false, false, null, null, null),
            new AccountResolutionService.AddressProfile(true, true, "BILLING", "San Francisco", "CA", "94105", "USA"),
            new AccountResolutionService.AddressProfile(true, true, "SHIPPING", "San Francisco", "CA", "94105", "USA"));
    }
}
