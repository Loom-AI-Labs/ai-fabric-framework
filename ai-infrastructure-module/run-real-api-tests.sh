#!/bin/bash

# Real API Integration Tests Runner
# This script runs tests that actually call OpenAI APIs

echo "🚀 Running Real API Integration Tests for AI Infrastructure Module"
echo "================================================================"

# Check if OpenAI API key is set
if [ -z "$OPENAI_API_KEY" ]; then
    echo "❌ ERROR: OPENAI_API_KEY environment variable is not set"
    echo ""
    echo "To run these tests, you need to:"
    echo "1. Get an OpenAI API key from https://platform.openai.com/api-keys"
    echo "2. Set the environment variable:"
    echo "   export OPENAI_API_KEY=your_actual_openai_api_key"
    echo "3. Run this script again"
    echo ""
    exit 1
fi

echo "✅ OpenAI API Key found: ${OPENAI_API_KEY:0:10}..."
echo ""

# Run the real API tests
echo "🧪 Running Real API Integration Tests..."
echo "This will make actual calls to OpenAI APIs and may incur costs."
echo ""

# Resolve the module directory regardless of the caller's working directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mvn -f "${SCRIPT_DIR}/ai-infrastructure-core/pom.xml" test -Dtest=EmbeddingProviderIntegrationTest

echo ""
echo "✅ Real API Integration Tests completed!"
echo "Check the output above for any failures or errors."