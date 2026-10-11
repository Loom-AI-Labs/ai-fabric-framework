#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
allowlist="${repo_root}/.github/semantic-decision-source-guard-allowlist.tsv"

guarded_roots=(
  "ai-infrastructure-module/ai-fabric-core/src/main/java/ai/fabric/intent"
  "ai-infrastructure-module/ai-fabric-chat-session/src/main/java/ai/fabric/chat/pipeline"
  "ai-infrastructure-module/ai-fabric-governance/src/main/java/ai/fabric/filter"
)

for root in "${guarded_roots[@]}"; do
  if [[ ! -d "${repo_root}/${root}" ]]; then
    echo "Semantic-decision source guard root is missing: ${root}" >&2
    exit 1
  fi
done
if [[ ! -f "${allowlist}" ]]; then
  echo "Semantic-decision source guard allowlist is missing: ${allowlist}" >&2
  exit 1
fi

# These identifiers name previously removed semantic substitutions. Keeping the
# signatures here makes an accidental reintroduction fail even if variable names
# are changed enough to evade the broader text-operation scan below.
forbidden_identifiers='tryQueryMentionMatch|queryMentionsType|isHighConfidenceReferentialFollowUp|containsStructuredTargetReference|rankDocumentEvidence|documentRelevanceScore|matchedTermScore|selectRelevantExcerpt|bestQueryMatchIndex|isComplexQuery|detectRuleBasedViolations|containsHateSpeech|containsHarassment|containsViolence|containsExplicitContent|containsSpam|safeDefaultResponse|syntheticSafeDefault|requiresRetrievalOrDefault|requiresGenerationOrDefault|requiresTargetResolutionOrDefault|needsAdvancedRagOrDefault|defaultIntentName|setRequiresRetrieval\(intent\.getType'

cd "${repo_root}"
if forbidden_output="$(rg -n --no-heading "${forbidden_identifiers}" "${guarded_roots[@]}" || true)" \
  && [[ -n "${forbidden_output}" ]]; then
  echo "Forbidden semantic-substitution identifiers found:" >&2
  echo "${forbidden_output}" >&2
  exit 1
fi

# Review text operations when their line also handles query/prompt/message-like
# data. This is intentionally scoped to runtime semantic packages. Reviewed
# structural/protocol/provenance uses must carry a narrow line marker below.
risk_pattern='(query|prompt|message|utterance|original|userEvidence|pinnedEvidence|text).{0,120}(contains\(|matches\(|regionMatches\(|startsWith\(|endsWith\(|split\(|Pattern\.compile)|((contains\(|matches\(|regionMatches\(|startsWith\(|endsWith\(|split\(|Pattern\.compile).{0,120}(query|prompt|message|utterance|original|userEvidence|pinnedEvidence|text))'
candidates="$(rg -n --no-heading -i "${risk_pattern}" "${guarded_roots[@]}" || true)"

violations=()
while IFS= read -r candidate; do
  [[ -z "${candidate}" ]] && continue
  path="${candidate%%:*}"
  remainder="${candidate#*:}"
  line_number="${remainder%%:*}"
  line_text="${remainder#*:}"
  reviewed=false

  while IFS=$'\t' read -r allowed_path marker owner reason; do
    [[ -z "${allowed_path}" || "${allowed_path}" == \#* ]] && continue
    if [[ "${path}" == "${allowed_path}" && "${line_text}" == *"${marker}"* ]]; then
      if [[ -z "${owner}" || -z "${reason}" ]]; then
        echo "Invalid source-guard allowlist entry for ${allowed_path}: owner and reason are required." >&2
        exit 1
      fi
      reviewed=true
      break
    fi
  done < "${allowlist}"

  if [[ "${reviewed}" != true ]]; then
    violations+=("${path}:${line_number}:${line_text}")
  fi
done <<< "${candidates}"

if (( ${#violations[@]} > 0 )); then
  echo "Unreviewed text matching in semantic-decision runtime packages:" >&2
  printf '  %s\n' "${violations[@]}" >&2
  echo "Remove semantic substitution or add a narrow reviewed entry with owner and reason." >&2
  exit 1
fi

echo "Semantic-decision source guard passed."
