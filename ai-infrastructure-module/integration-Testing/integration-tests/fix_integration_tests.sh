#!/bin/bash

# Fix integration tests to work with new vector storage architecture

for file in src/test/java/ai/fabric/it/*.java; do
    if grep -q "getEmbeddings()" "$file"; then
        echo "Fixing $file"

        # Remove duplicate VectorManagementService declarations and @Autowired annotations
        sed -i '/@Autowired/d' "$file"
        sed -i '/private VectorManagementService vectorManagementService;/d' "$file"

        # Add proper import if not exists
        if ! grep -q "VectorManagementService" "$file"; then
            sed -i '/import ai.fabric.service.AICapabilityService;/a import ai.fabric.service.VectorManagementService;' "$file"
        fi

        # Add single VectorManagementService injection after other @Autowired fields
        sed -i '/@Autowired/a\    @Autowired\n    private VectorManagementService vectorManagementService;' "$file"

        # Fix getEmbeddings() calls - replace with vectorId checks
        sed -i 's/assertNotNull(entity\.getEmbeddings(), "Should have embeddings");/assertNotNull(entity.getVectorId(), "Should have vector ID");/g' "$file"
        sed -i 's/assertFalse(entity\.getEmbeddings()\.isEmpty(), "Embeddings should not be empty");/assertFalse(entity.getVectorId().isEmpty(), "Vector ID should not be empty");/g' "$file"

        # Fix other getEmbeddings() patterns that were incorrectly replaced
        sed -i 's/entity\.getVectorId()\.size()/entity.getVectorId().length()/g' "$file"
        sed -i 's/\.getVectorId()\.size()/\.getVectorId().length()/g' "$file"

        # Add vector existence check after vectorId checks
        sed -i '/assertFalse(entity\.getVectorId()\.isEmpty(), "Vector ID should not be empty");/a\        \n        // Verify vector exists in vector database\n        assertTrue(vectorManagementService.vectorExists(entity.getEntityType(), entity.getEntityId()), \n                  "Vector should exist in vector database");' "$file"
    fi
done

echo "Integration tests fixed successfully!"
