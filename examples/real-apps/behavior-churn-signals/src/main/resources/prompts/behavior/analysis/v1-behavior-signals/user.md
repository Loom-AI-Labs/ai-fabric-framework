{{user_context_section}}
{{previous_analysis_section}}
=== NEW EVENTS ({{events_count}}) ===
{{new_events_lines}}

Analyze how this user's behavior has evolved.
Use the previous analysis as the starting point, then update it based on the new event sequence.
If the new events are mostly positive recovery signals after a negative baseline, show whether risk is resolved, partially reduced, or still urgent.
For mixed evidence, keep the analysis balanced: name both the remaining blocker and the recovery signal.

Output one compact JSON object only. Prefer concise evidence summaries over long explanations.
If this is the first analysis for the user, set trend to NEW_USER unless the event sequence clearly shows rapid improvement or decline.
Always map the strongest operator need into insights.action_family:
- RETENTION_OFFER for billing failures, cancellation intent, refund pressure, or renewal risk
- EXPANSION_FOLLOW_UP for healthy adoption, positive sentiment, upgrade signals, or increasing usage
- ADOPTION_HELP for onboarding confusion, repeated help searches, setup friction, or unresolved usage questions
- ENGINEERING_ESCALATION for release regressions, repeated feature errors, timeouts, stopped-loading complaints, or performance failures. Prefer this over ADOPTION_HELP when support complaints are caused by a release or product error.
- PROACTIVE_CHECK_IN for no-login, quiet usage drop, or disengagement without direct complaint
- MONITOR_ONLY for steady low-risk behavior with no clear operator intervention

Return valid JSON only.
