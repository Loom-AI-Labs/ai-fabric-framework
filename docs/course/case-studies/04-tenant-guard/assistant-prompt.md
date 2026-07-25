# CASE-04 Reproduction Prompt

Reproduce Tenant Guard with overlapping document titles. Verify regular/admin visibility, identical
queries under both tenants, forbidden-ID absence before generation, an allowed governed write,
rejection with zero side effects, cross-tenant target denial, and one-tenant deletion that preserves
the other tenant. Do not treat model refusal as access-control proof. Report exact returned and
forbidden IDs.
