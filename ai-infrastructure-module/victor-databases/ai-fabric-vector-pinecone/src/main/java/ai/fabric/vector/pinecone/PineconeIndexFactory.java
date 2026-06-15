package ai.fabric.vector.pinecone;

import io.pinecone.clients.Index;
import io.pinecone.configs.PineconeConnection;

/**
 * Factory for creating Pinecone {@link Index} clients.
 *
 * <p>This provides a production seam for swapping Index creation (e.g. for testing or custom wiring)
 * without introducing test-only constructors into the vector database service.</p>
 */
@FunctionalInterface
public interface PineconeIndexFactory {

    Index create(PineconeConnection connection, String indexName);
}

