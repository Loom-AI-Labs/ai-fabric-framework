{{user_context_section}}
{{previous_analysis_section}}
=== NEW EVENTS ({{events_count}}) ===
{{new_events_lines}}

Analyze how this user's behavior has evolved.

Output one compact JSON object only. Prefer concise evidence summaries over long explanations.
If this is the first analysis for the user, set trend to NEW_USER unless the event sequence clearly shows rapid improvement or decline.
Map the strongest operator need into insights.action_family when possible:
- RETENTION_OFFER for billing failures, cancellation intent, refund pressure, or renewal risk
- EXPANSION_FOLLOW_UP for healthy adoption, positive sentiment, upgrade signals, or increasing usage
- ADOPTION_HELP for onboarding confusion, repeated help searches, setup friction, or unresolved usage questions
- ENGINEERING_ESCALATION for release regressions, repeated feature errors, timeouts, or performance failures
- PROACTIVE_CHECK_IN for no-login, quiet usage drop, or disengagement without direct complaint

Return valid JSON only.
