{{user_context_section}}
{{previous_analysis_section}}
=== NEW EVENTS ({{events_count}}) ===
{{new_events_lines}}

Analyze how this user's behavior has evolved.
Use previous analysis as the starting point when present, then update it based on the new event sequence.
If new events are mostly positive recovery signals after a negative baseline, show whether risk is resolved, partially reduced, or still urgent.
If new events are mostly negative after a healthy baseline, reflect the deterioration in churn.risk, sentiment, trend, patterns, recommendations, and insights.
For mixed evidence, keep the analysis balanced: name both remaining blockers and recovery signals.

Return one compact JSON object only. Prefer concise evidence summaries over long explanations.
If this is the first analysis for the user, set trend to NEW_USER unless the event sequence clearly shows rapid improvement or decline.
