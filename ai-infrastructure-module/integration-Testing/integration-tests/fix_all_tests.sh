#!/bin/bash

# Fix all integration tests to work with new vector storage architecture

echo "🔧 Fixing integration tests for vector storage migration..."

# List of test files to fix
test_files=(
    "MockIntegrationTest.java"
    "RealAPIIntegrationTest.java"
    "ComprehensiveSequence13IntegrationTest.java"
    "CreativeAIScenariosTest.java"
)

for test_file in "${test_files[@]}"; do
    file_path="src/test/java/ai/fabric/it/$test_file"

    if [ -f "$file_path" ]; then
        echo "Fixing $test_file..."

        # Add VectorManagementService import if not exists
        if ! grep -q "VectorManagementService" "$file_path"; then
            sed -i '/import ai.fabric.service.AICapabilityService;/a import ai.fabric.service.VectorManagementService;' "$file_path"
        fi

        # Add VectorManagementService injection if not exists
        if ! grep -q "private VectorManagementService vectorManagementService;" "$file_path"; then
            # Find the last @Autowired field and add after it
            sed -i '/@Autowired/a\    @Autowired\n    private VectorManagementService vectorManagementService;' "$file_path"
        fi

        # Replace getEmbeddings() patterns
        sed -i 's/assertNotNull(entity\.getEmbeddings(), "Should have embeddings");/assertNotNull(entity.getVectorId(), "Should have vector ID");/g' "$file_path"
        sed -i 's/assertFalse(entity\.getEmbeddings()\.isEmpty(), "Embeddings should not be empty");/assertFalse(entity.getVectorId().isEmpty(), "Vector ID should not be empty");/g' "$file_path"

        # Fix size() calls on vectorId (should be length())
        sed -i 's/entity\.getVectorId()\.size()/entity.getVectorId().length()/g' "$file_path"
        sed -i 's/\.getVectorId()\.size()/\.getVectorId().length()/g' "$file_path"

        # Add vector existence check after vectorId checks
        sed -i '/assertFalse(entity\.getVectorId()\.isEmpty(), "Vector ID should not be empty");/a\        \n        // Verify vector exists in vector database\n        assertTrue(vectorManagementService.vectorExists(entity.getEntityType(), entity.getEntityId()), \n                  "Vector should exist in vector database");' "$file_path"

        # Fix remaining getEmbeddings() patterns
        sed -i 's/entity\.getEmbeddings()/entity.getVectorId()/g' "$file_path"
        sed -i 's/\.getEmbeddings()/\.getVectorId()/g' "$file_path"

        # Fix logging statements
        sed -i 's/Embeddings generated:/Vector ID:/g' "$file_path"
        sed -i 's/Embeddings count:/Vector ID:/g' "$file_path"
        sed -i 's/entity\.getVectorId()\.size()/entity.getVectorId().length()/g' "$file_path"

        echo "✅ Fixed $test_file"
    else
        echo "⚠️  File not found: $file_path"
    fi
done

echo "🎉 All integration tests fixed successfully!"
