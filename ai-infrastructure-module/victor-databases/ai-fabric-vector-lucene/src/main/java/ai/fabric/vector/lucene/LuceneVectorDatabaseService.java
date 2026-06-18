package ai.fabric.vector.lucene;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.VectorRecord;
import ai.fabric.dto.VectorScanPage;
import ai.fabric.dto.VectorScanRequest;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.exception.AIServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.LockObtainFailedException;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Lucene Vector Database Service
 * 
 * This service provides vector database operations using Apache Lucene 9+ with native k-NN search.
 * Uses KnnVectorField and KnnVectorQuery for optimized approximate nearest neighbor search.
 * It's designed for development and testing environments where external vector databases
 * are not available or needed.
 * 
 * This implementation delegates similarity calculations to Lucene's native k-NN implementation,
 * making it swappable with production vector databases (Pinecone, Qdrant, etc.) that also handle
 * similarity internally.
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class LuceneVectorDatabaseService implements VectorDatabaseService {
    
    private final AIProviderConfig config;
    
    @Value("${ai.vector-db.lucene.index-path:./data/lucene-vector-index}")
    private String indexPath;
    
    @Value("${ai.vector-db.lucene.similarity-threshold:0.7}")
    private double similarityThreshold;
    
    @Value("${ai.vector-db.lucene.max-results:100}")
    private int maxResults;
    
    @Value("${ai.vector-db.lucene.vector-dimension:1536}")
    private int vectorDimension;

    @Value("${ai.vector-db.lucene.cleanup-on-close:false}")
    private boolean cleanupOnClose;

    private static final String VECTOR_FIELD = "vector";
    private static final String VECTOR_ID_FIELD = "vectorId";
    private static final String ENTITY_ID_FIELD = "entityId";
    private static final String ENTITY_TYPE_FIELD = "entityType";
    private static final String CREATED_AT_FIELD = "createdAt";
    private static final String UPDATED_AT_FIELD = "updatedAt";
    private static final String CREATED_AT_MILLIS_FIELD = "createdAtMillis";
    private static final String UPDATED_AT_MILLIS_FIELD = "updatedAtMillis";
    
    private static final Map<Path, SharedIndex> INDEX_CACHE = new ConcurrentHashMap<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Directory directory;
    private IndexWriter indexWriter;
    private IndexReader indexReader;
    private IndexSearcher indexSearcher;
    private StandardAnalyzer analyzer;
    private SharedIndex sharedIndex;
    private Path resolvedIndexPath;
    
    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Lucene Vector Database at: {}", indexPath);
            
            // Create index directory if it doesn't exist
            resolvedIndexPath = Paths.get(indexPath).toAbsolutePath().normalize();
            if (!Files.exists(resolvedIndexPath)) {
                Files.createDirectories(resolvedIndexPath);
            }

            analyzer = new StandardAnalyzer();
            sharedIndex = INDEX_CACHE.compute(resolvedIndexPath, (path, existing) -> {
                if (existing != null) {
                    existing.retain();
                    return existing;
                }

                try {
                    Directory newDirectory = FSDirectory.open(path);
                    IndexWriterConfig config = new IndexWriterConfig(analyzer);
                    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                    IndexWriter writer;
                    try {
                        writer = new IndexWriter(newDirectory, config);
                    } catch (LockObtainFailedException lockException) {
                        log.warn("Existing Lucene lock detected at {}. Attempting recovery.", path, lockException);
                        writer = recoverFromLock(newDirectory, config, path);
                    }
                    return new SharedIndex(newDirectory, writer);
                } catch (IOException ioException) {
                    throw new UncheckedIOException(ioException);
                }
            });

            directory = sharedIndex.directory;
            indexWriter = sharedIndex.writer;

            // Initialize reader and searcher
            refreshReader();
            
            log.info("Lucene Vector Database initialized successfully");
            
        } catch (Exception e) {
            log.error("Failed to initialize Lucene Vector Database", e);
            throw new AIServiceException("Failed to initialize Lucene Vector Database", e);
        }
    }
    
    @PreDestroy
    public void cleanup() {
        try {
            log.debug("Closing Lucene Vector Database");

            boolean removedFromCache = false;
            if (indexWriter != null) {
                if (indexReader != null) {
                    indexReader.close();
                }
                if (resolvedIndexPath != null) {
                    SharedIndex indexToClose = null;
                    synchronized (INDEX_CACHE) {
                        SharedIndex shared = INDEX_CACHE.get(resolvedIndexPath);
                        if (shared != null && shared.release()) {
                            INDEX_CACHE.remove(resolvedIndexPath);
                            indexToClose = shared;
                            removedFromCache = true;
                        }
                    }
                    if (indexToClose != null) {
                        closeSharedIndex(indexToClose, resolvedIndexPath);
                        removedFromCache = true;
                        directory = null;
                    }
                }
            } else if (indexReader != null) {
                indexReader.close();
            }
            if (analyzer != null) {
                analyzer.close();
            }

            if (cleanupOnClose && removedFromCache && resolvedIndexPath != null) {
                deleteDirectoryRecursively(resolvedIndexPath);
            }

            log.debug("Lucene Vector Database closed successfully");

        } catch (Exception e) {
            log.error("Error closing Lucene Vector Database", e);
        }
    }
    
    @Override
    public String storeVector(String entityType, String entityId, String content, 
                             List<Double> embedding, Map<String, Object> metadata) {
        try {
            log.debug("Storing vector in Lucene for entity {} of type {}", entityId, entityType);

            String vectorId = UUID.randomUUID().toString();
            long nowMillis = System.currentTimeMillis();
            Document document = buildDocument(vectorId, entityType, entityId, content, embedding, metadata, nowMillis, nowMillis);

            indexWriter.addDocument(document);
            indexWriter.commit();
            refreshReader();

            log.debug("Successfully stored vector in Lucene for entity {} of type {} with vectorId {}",
                entityId, entityType, vectorId);

            return vectorId;

        } catch (Exception e) {
            log.error("Error storing vector in Lucene", e);
            throw new AIServiceException("Failed to store vector in Lucene", e);
        }
    }
    
    @Override
    public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
        try {
            String queryText = request != null ? request.getQuery() : "";
            log.debug("Searching vectors in Lucene using native k-NN for query: {}", queryText);
            
            long startTime = System.currentTimeMillis();
            
            // Convert query vector to float[] for Lucene k-NN
            float[] queryVectorArray = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                queryVectorArray[i] = queryVector.get(i).floatValue();
            }
            
            // Use Lucene 9+ native k-NN search with KnnVectorQuery
            // This provides optimized approximate nearest neighbor search
            // The vector database handles similarity calculation internally
            int limit = resolveResultLimit(request);
            double threshold = resolveSimilarityThreshold(request);
            int k = Math.max(limit, Math.min(limit * 2, Math.max(limit, maxResults) * 2)); // Get more candidates for threshold filtering
            KnnVectorQuery vectorQuery = new KnnVectorQuery(VECTOR_FIELD, queryVectorArray, k);
            
            // Apply entity type filter if specified
            Query filterQuery = null;
            String entityType = request != null ? request.getEntityType() : null;
            if (entityType != null && !entityType.trim().isEmpty()) {
                filterQuery = new TermQuery(new Term(ENTITY_TYPE_FIELD, entityType));
            }

            // Perform k-NN search (Lucene handles similarity internally)
            TopDocs topDocs;
            try {
                if (filterQuery != null) {
                    BooleanQuery.Builder boolQueryBuilder = new BooleanQuery.Builder();
                    boolQueryBuilder.add(vectorQuery, BooleanClause.Occur.MUST);
                    boolQueryBuilder.add(filterQuery, BooleanClause.Occur.FILTER);
                    topDocs = indexSearcher.search(boolQueryBuilder.build(), k);
                } else {
                    topDocs = indexSearcher.search(vectorQuery, k);
                }
            } catch (org.apache.lucene.store.AlreadyClosedException e) {
                // IndexReader was closed during search (concurrency issue) - refresh and retry once
                log.debug("IndexReader closed during search, refreshing and retrying");
                synchronized (this) {
                    refreshReader();
                    if (indexSearcher == null) {
                        throw new AIServiceException("IndexSearcher not available after refresh");
                    }
                    if (filterQuery != null) {
                        BooleanQuery.Builder boolQueryBuilder = new BooleanQuery.Builder();
                        boolQueryBuilder.add(vectorQuery, BooleanClause.Occur.MUST);
                        boolQueryBuilder.add(filterQuery, BooleanClause.Occur.FILTER);
                        topDocs = indexSearcher.search(boolQueryBuilder.build(), k);
                    } else {
                        topDocs = indexSearcher.search(vectorQuery, k);
                    }
                }
            }
            
            ScoreDoc[] hits = topDocs.scoreDocs;
            
            // Process results - Lucene has already calculated similarity scores
            List<Map<String, Object>> results = new ArrayList<>();
            for (ScoreDoc hit : hits) {
                Document doc;
                try {
                    doc = indexSearcher.doc(hit.doc);
                } catch (org.apache.lucene.store.AlreadyClosedException e) {
                    // IndexReader was closed during doc retrieval - refresh and retry once
                    log.debug("IndexReader closed during doc retrieval, refreshing and retrying");
                    synchronized (this) {
                        refreshReader();
                        if (indexSearcher == null) {
                            continue; // Skip this result if searcher unavailable
                        }
                        doc = indexSearcher.doc(hit.doc);
                    }
                }
                
                // Lucene's k-NN search already provides similarity scores
                // The score from Lucene is the cosine similarity
                // Normalize to [0, 1] range if needed (Lucene scores are typically already normalized)
                double similarity = hit.score;
                
                // Apply threshold filter
                if (similarity >= threshold) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", doc.get(ENTITY_ID_FIELD));
                    result.put("vectorId", doc.get(VECTOR_ID_FIELD));
                    result.put("content", doc.get("content"));
                    result.put("entityType", doc.get(ENTITY_TYPE_FIELD));
                    result.put("vectorSpace", doc.get(ENTITY_TYPE_FIELD));
                    result.put("metadata", doc.get("metadata"));
                    result.put("score", similarity);
                    result.put("similarity", similarity);
                    
                    results.add(result);
                    
                    // Stop once we have enough results
                    if (results.size() >= limit) {
                        break;
                    }
                }
            }
            
            long processingTime = System.currentTimeMillis() - startTime;
            
            log.debug("Found {} results using Lucene native k-NN in {}ms", results.size(), processingTime);
            
            return AISearchResponse.builder()
                .results(results)
                .totalResults(results.size())
                .maxScore(results.isEmpty() ? 0.0 : (Double) results.get(0).get("similarity"))
                .processingTimeMs(Long.valueOf(processingTime))
                .requestId(UUID.randomUUID().toString())
                .query(queryText)
                .model(config.resolveEmbeddingDefaults().model())
                .build();
                
        } catch (Exception e) {
            log.error("Error searching vectors in Lucene", e);
            throw new AIServiceException("Failed to search vectors in Lucene", e);
        }
    }
    
    @Override
    public boolean removeVector(String entityType, String entityId) {
        try {
            log.debug("Removing vector from Lucene for entity {} of type {}", entityId, entityType);
            
            BooleanQuery.Builder deleteQuery = new BooleanQuery.Builder();
            deleteQuery.add(new TermQuery(new Term(ENTITY_TYPE_FIELD, entityType)), BooleanClause.Occur.MUST);
            deleteQuery.add(new TermQuery(new Term(ENTITY_ID_FIELD, entityId)), BooleanClause.Occur.MUST);

            long deletedCount = indexWriter.deleteDocuments(deleteQuery.build());
            indexWriter.commit();
            
            // Refresh reader
            refreshReader();
            
            boolean removed = deletedCount > 0;
            log.debug("Successfully removed vector from Lucene for entity {} of type {}: {}", 
                     entityId, entityType, removed);
            
            return removed;
            
        } catch (Exception e) {
            log.error("Error removing vector from Lucene", e);
            throw new AIServiceException("Failed to remove vector from Lucene", e);
        }
    }
    
    @Override
    public Map<String, Object> getStatistics() {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            if (indexReader != null) {
                stats.put("totalVectors", indexReader.numDocs());
                stats.put("indexPath", indexPath);
                stats.put("similarityThreshold", similarityThreshold);
                stats.put("maxResults", maxResults);
                
                // Get entity type counts
                Map<String, Integer> entityTypeCounts = new HashMap<>();
                for (int i = 0; i < indexReader.numDocs(); i++) {
                    Document doc = indexReader.document(i);
                    String entityType = doc.get("entityType");
                    entityTypeCounts.merge(entityType, 1, Integer::sum);
                }
                stats.put("entityTypeCounts", entityTypeCounts);
                stats.put("entityTypes", entityTypeCounts.keySet());
            }
            
            return stats;
            
        } catch (Exception e) {
            log.error("Error getting Lucene statistics", e);
            return Map.of("error", "Failed to get statistics");
        }
    }
    
    @Override
    public long clearVectors() {
        try {
            log.debug("Clearing all vectors from Lucene");
            if (indexWriter == null) {
                log.debug("IndexWriter not initialized; nothing to clear");
                return 0;
            }
            
            long countBefore = indexReader != null ? indexReader.numDocs() : 0;
            indexWriter.deleteAll();
            indexWriter.commit();
            refreshReader();
            
            log.debug("Successfully cleared {} vectors from Lucene", countBefore);
            return countBefore;
            
        } catch (Exception e) {
            log.error("Error clearing vectors from Lucene", e);
            throw new AIServiceException("Failed to clear vectors from Lucene", e);
        }
    }
    
    @Override
    public boolean updateVector(String vectorId, String entityType, String entityId, 
                               String content, List<Double> embedding, Map<String, Object> metadata) {
        try {
            log.debug("Updating vector {} in Lucene for entity {} of type {}", vectorId, entityId, entityType);

            long preservedCreatedAtMillis = findCreatedAtMillisByVectorId(vectorId).orElse(System.currentTimeMillis());
            long nowMillis = System.currentTimeMillis();
            
            Term term = new Term(VECTOR_ID_FIELD, vectorId);
            long deletedCount = indexWriter.deleteDocuments(term);

            if (deletedCount == 0) {
                log.warn("Vector {} not found for update", vectorId);
                return false;
            }

            Document document = buildDocument(vectorId, entityType, entityId, content, embedding, metadata, preservedCreatedAtMillis, nowMillis);

            indexWriter.addDocument(document);
            indexWriter.commit();
            refreshReader();

            log.debug("Successfully updated vector {} in Lucene", vectorId);
            return true;

        } catch (Exception e) {
            log.error("Error updating vector in Lucene", e);
            throw new AIServiceException("Failed to update vector in Lucene", e);
        }
    }

    @Override
    public boolean supportsVectorScan() {
        return true;
    }

    @Override
    public boolean supportsMetadataFiltering() {
        // Metadata is stored as JSON (StoredField) and not indexed for filtering yet.
        // scan(...) uses the default in-memory filtering implementation.
        return false;
    }

    @Override
    public VectorScanPage scan(VectorScanRequest request) {
        return VectorDatabaseService.super.scan(request);
    }

    private Document buildDocument(String vectorId, String entityType, String entityId, String content,
                                   List<Double> embedding, Map<String, Object> metadata,
                                   long createdAtMillis, long updatedAtMillis) {
        Document doc = new Document();

        doc.add(new StringField(VECTOR_ID_FIELD, vectorId, Field.Store.YES));
        doc.add(new StringField(ENTITY_ID_FIELD, entityId, Field.Store.YES));
        doc.add(new StringField(ENTITY_TYPE_FIELD, entityType, Field.Store.YES));
        doc.add(new StoredField("content", content));

        float[] vectorArray = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vectorArray[i] = embedding.get(i).floatValue();
        }
        doc.add(new KnnVectorField(VECTOR_FIELD, vectorArray, VectorSimilarityFunction.COSINE));

        String embeddingText = embedding.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        doc.add(new StoredField("embedding", embeddingText));

        serializeMetadata(metadata).ifPresent(metadataJson -> doc.add(new StoredField("metadata", metadataJson)));

        doc.add(new StringField("storedAt", String.valueOf(updatedAtMillis), Field.Store.YES));
        doc.add(new StringField(CREATED_AT_FIELD, String.valueOf(createdAtMillis), Field.Store.YES));
        doc.add(new StringField(UPDATED_AT_FIELD, String.valueOf(updatedAtMillis), Field.Store.YES));

        doc.add(new LongPoint(CREATED_AT_MILLIS_FIELD, createdAtMillis));
        doc.add(new LongPoint(UPDATED_AT_MILLIS_FIELD, updatedAtMillis));
        doc.add(new NumericDocValuesField(CREATED_AT_MILLIS_FIELD, createdAtMillis));
        doc.add(new NumericDocValuesField(UPDATED_AT_MILLIS_FIELD, updatedAtMillis));

        return doc;
    }

    private int resolveResultLimit(AISearchRequest request) {
        int configuredMaxResults = maxResults > 0 ? maxResults : 100;
        int requestedLimit = request != null && request.getLimit() != null && request.getLimit() > 0
            ? request.getLimit()
            : 10;
        return Math.min(requestedLimit, configuredMaxResults);
    }

    private double resolveSimilarityThreshold(AISearchRequest request) {
        double configuredThreshold = Double.isFinite(similarityThreshold)
            ? Math.max(0.0, Math.min(1.0, similarityThreshold))
            : 0.7;
        if (request == null || request.getThreshold() == null || !Double.isFinite(request.getThreshold())) {
            return configuredThreshold;
        }
        return Math.max(0.0, Math.min(1.0, request.getThreshold()));
    }

    private Optional<String> serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            throw new AIServiceException("Failed to serialize vector metadata", e);
        }
    }

    private Optional<Long> findCreatedAtMillisByVectorId(String vectorId) {
        if (vectorId == null || vectorId.isBlank() || indexSearcher == null) {
            return Optional.empty();
        }
        try {
            Term term = new Term(VECTOR_ID_FIELD, vectorId);
            Query query = new TermQuery(term);

            TopDocs topDocs = indexSearcher.search(query, 1);
            if (topDocs.totalHits.value <= 0) {
                return Optional.empty();
            }

            Document doc = indexSearcher.doc(topDocs.scoreDocs[0].doc);
            String raw = doc.get(CREATED_AT_FIELD);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(raw));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
    
    @Override
    public Optional<VectorRecord> getVector(String vectorId) {
        try {
            log.debug("Getting vector {} from Lucene", vectorId);
            
            Term term = new Term(VECTOR_ID_FIELD, vectorId);
            Query query = new TermQuery(term);

            TopDocs topDocs = indexSearcher.search(query, 1);
            if (topDocs.totalHits.value > 0) {
                Document doc = indexSearcher.doc(topDocs.scoreDocs[0].doc);
                return convertDocumentToVectorRecord(doc);
            }
            
            return Optional.empty();
            
        } catch (Exception e) {
            log.error("Error getting vector from Lucene", e);
            return Optional.empty();
        }
    }
    
    @Override
    public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
        try {
            log.debug("Getting vector from Lucene for entity {} of type {}", entityId, entityType);
            
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(new TermQuery(new Term(ENTITY_TYPE_FIELD, entityType)), BooleanClause.Occur.MUST);
            builder.add(new TermQuery(new Term(ENTITY_ID_FIELD, entityId)), BooleanClause.Occur.MUST);

            TopDocs topDocs = indexSearcher.search(builder.build(), 1);
            if (topDocs.totalHits.value > 0) {
                Document doc = indexSearcher.doc(topDocs.scoreDocs[0].doc);
                return convertDocumentToVectorRecord(doc);
            }
            
            return Optional.empty();
            
        } catch (Exception e) {
            log.error("Error getting vector by entity from Lucene", e);
            return Optional.empty();
        }
    }
    
    @Override
    public AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, 
                                              int limit, double threshold) {
        AISearchRequest request = AISearchRequest.builder()
            .query("")
            .entityType(entityType)
            .limit(limit)
            .threshold(threshold)
            .build();
        
        return search(queryVector, request);
    }
    
    @Override
    public boolean removeVectorById(String vectorId) {
        try {
            log.debug("Removing vector {} from Lucene", vectorId);
            
            Term term = new Term(VECTOR_ID_FIELD, vectorId);
            long deletedCount = indexWriter.deleteDocuments(term);
            indexWriter.commit();
            
            // Refresh reader
            refreshReader();
            
            boolean removed = deletedCount > 0;
            log.debug("Successfully removed vector {} from Lucene: {}", vectorId, removed);
            
            return removed;
            
        } catch (Exception e) {
            log.error("Error removing vector by ID from Lucene", e);
            throw new AIServiceException("Failed to remove vector by ID from Lucene", e);
        }
    }
    
    @Override
    public List<String> batchStoreVectors(List<VectorRecord> vectors) {
        try {
            log.debug("Batch storing {} vectors in Lucene", vectors.size());
            
            List<String> vectorIds = new ArrayList<>();
            
            for (VectorRecord vector : vectors) {
                String vectorId = storeVector(
                    vector.getEntityType(),
                    vector.getEntityId(),
                    vector.getContent(),
                    vector.getEmbedding(),
                    vector.getMetadata()
                );
                vectorIds.add(vectorId);
            }
            
            log.debug("Successfully batch stored {} vectors in Lucene", vectorIds.size());
            return vectorIds;
            
        } catch (Exception e) {
            log.error("Error batch storing vectors in Lucene", e);
            throw new AIServiceException("Failed to batch store vectors in Lucene", e);
        }
    }
    
    @Override
    public int batchUpdateVectors(List<VectorRecord> vectors) {
        try {
            log.debug("Batch updating {} vectors in Lucene", vectors.size());
            
            int updatedCount = 0;
            
            for (VectorRecord vector : vectors) {
                if (updateVector(
                    vector.getVectorId(),
                    vector.getEntityType(),
                    vector.getEntityId(),
                    vector.getContent(),
                    vector.getEmbedding(),
                    vector.getMetadata()
                )) {
                    updatedCount++;
                }
            }
            
            log.debug("Successfully batch updated {} vectors in Lucene", updatedCount);
            return updatedCount;
            
        } catch (Exception e) {
            log.error("Error batch updating vectors in Lucene", e);
            throw new AIServiceException("Failed to batch update vectors in Lucene", e);
        }
    }
    
    @Override
    public int batchRemoveVectors(List<String> vectorIds) {
        try {
            log.debug("Batch removing {} vectors from Lucene", vectorIds.size());
            
            int removedCount = 0;
            
            for (String vectorId : vectorIds) {
                if (removeVectorById(vectorId)) {
                    removedCount++;
                }
            }
            
            log.debug("Successfully batch removed {} vectors from Lucene", removedCount);
            return removedCount;
            
        } catch (Exception e) {
            log.error("Error batch removing vectors from Lucene", e);
            throw new AIServiceException("Failed to batch remove vectors from Lucene", e);
        }
    }
    
    @Override
    public List<VectorRecord> getVectorsByEntityType(String entityType) {
        try {
            log.debug("Getting all vectors for entity type {} from Lucene", entityType);
            
            Query query = new TermQuery(new Term(ENTITY_TYPE_FIELD, entityType));

            TopDocs topDocs = indexSearcher.search(query, Integer.MAX_VALUE);
            List<VectorRecord> vectors = new ArrayList<>();
            
            for (ScoreDoc hit : topDocs.scoreDocs) {
                Document doc = indexSearcher.doc(hit.doc);
                convertDocumentToVectorRecord(doc).ifPresent(vectors::add);
            }
            
            log.debug("Found {} vectors for entity type {} in Lucene", vectors.size(), entityType);
            return vectors;
            
        } catch (Exception e) {
            log.error("Error getting vectors by entity type from Lucene", e);
            throw new AIServiceException("Failed to get vectors by entity type from Lucene", e);
        }
    }
    
    @Override
    public long getVectorCountByEntityType(String entityType) {
        try {
            Query query = new TermQuery(new Term(ENTITY_TYPE_FIELD, entityType));

            if (indexSearcher == null) {
                log.debug("IndexSearcher not initialized; vector count for entity type {} is 0", entityType);
                return 0;
            }

            try {
                // Lucene provides a dedicated count() API; using search(query, 0) can return 0 hits.
                return (long) indexSearcher.count(query);
            } catch (org.apache.lucene.store.AlreadyClosedException e) {
                // IndexReader was closed during count (concurrency issue) - refresh and retry once
                log.debug("IndexReader closed during count, refreshing and retrying for entity type {}", entityType);
                synchronized (this) {
                    refreshReader();
                    if (indexSearcher == null) {
                        return 0;
                    }
                    return (long) indexSearcher.count(query);
                }
            }
            
        } catch (Exception e) {
            log.error("Error getting vector count by entity type from Lucene", e);
            return 0;
        }
    }
    
    @Override
    public boolean vectorExists(String entityType, String entityId) {
        try {
            if (indexSearcher == null) {
                log.debug("IndexSearcher not initialized; vector for entity {} of type {} does not exist", entityId, entityType);
                return false;
            }
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(new TermQuery(new Term(ENTITY_TYPE_FIELD, entityType)), BooleanClause.Occur.MUST);
            builder.add(new TermQuery(new Term(ENTITY_ID_FIELD, entityId)), BooleanClause.Occur.MUST);

            try {
                TopDocs topDocs = indexSearcher.search(builder.build(), 1);
                return topDocs.totalHits.value > 0;
            } catch (org.apache.lucene.store.AlreadyClosedException e) {
                // IndexReader was closed during search (concurrency issue) - refresh and retry once
                log.debug("IndexReader closed during search, refreshing and retrying for entity {} of type {}", entityId, entityType);
                synchronized (this) {
                    refreshReader();
                    if (indexSearcher == null) {
                        return false;
                    }
                    TopDocs topDocs = indexSearcher.search(builder.build(), 1);
                    return topDocs.totalHits.value > 0;
                }
            }
            
        } catch (Exception e) {
            log.error("Error checking if vector exists in Lucene", e);
            return false;
        }
    }
    
    @Override
    public long clearVectorsByEntityType(String entityType) {
        try {
            log.debug("Clearing all vectors for entity type {} from Lucene", entityType);
            if (indexWriter == null) {
                log.debug("IndexWriter not initialized; nothing to clear for entity type {}", entityType);
                return 0;
            }
            
            Query query = new TermQuery(new Term(ENTITY_TYPE_FIELD, entityType));

            long countBefore = indexSearcher.count(query);
            indexWriter.deleteDocuments(new Term(ENTITY_TYPE_FIELD, entityType));
            indexWriter.commit();
            refreshReader();
            
            log.debug("Successfully cleared {} vectors for entity type {} from Lucene", countBefore, entityType);
            return countBefore;
            
        } catch (Exception e) {
            log.error("Error clearing vectors by entity type from Lucene", e);
            throw new AIServiceException("Failed to clear vectors by entity type from Lucene", e);
        }
    }
    
    /**
     * Convert Lucene document to VectorRecord
     */
    private Optional<VectorRecord> convertDocumentToVectorRecord(Document doc) {
        try {
            String embeddingText = doc.get("embedding");
            List<Double> embedding = parseEmbedding(embeddingText);
            
            Map<String, Object> metadata = new HashMap<>();
            String metadataJson = doc.get("metadata");
            if (metadataJson != null && !metadataJson.trim().isEmpty()) {
                metadata.put("raw", metadataJson);
                try {
                    Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                    metadata.putAll(parsed);
                } catch (Exception parseException) {
                    log.warn("Unable to deserialize metadata JSON: {}", metadataJson, parseException);
                }
            }
            
            return Optional.of(VectorRecord.builder()
                .vectorId(doc.get("vectorId"))
                .entityType(doc.get("entityType"))
                .entityId(doc.get("entityId"))
                .content(doc.get("content"))
                .embedding(embedding)
                .metadata(metadata)
                .createdAt(parseTimestamp(doc.get(CREATED_AT_FIELD)))
                .updatedAt(parseTimestamp(doc.get(UPDATED_AT_FIELD)))
                .active(true)
                .version(1)
                .build());
                
        } catch (Exception e) {
            log.error("Error converting document to VectorRecord", e);
            return Optional.empty();
        }
    }

    private List<Double> parseEmbedding(String embeddingText) {
        if (embeddingText == null || embeddingText.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(embeddingText.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(Double::parseDouble)
            .collect(Collectors.toList());
    }
    
    /**
     * Parse timestamp from string
     */
    private java.time.LocalDateTime parseTimestamp(String timestampStr) {
        try {
            if (timestampStr != null) {
                long timestamp = Long.parseLong(timestampStr);
                return java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(timestamp),
                    java.time.ZoneId.systemDefault()
                );
            }
        } catch (Exception e) {
            log.warn("Error parsing timestamp: {}", timestampStr, e);
        }
        return java.time.LocalDateTime.now();
    }
    
    /**
     * Note: Similarity calculation is now handled by Lucene's native k-NN search.
     * This eliminates the need for manual cosine similarity calculations.
     * The VectorDatabaseService abstraction ensures that all implementations
     * (Lucene, Pinecone, Qdrant, etc.) handle similarity internally.
     */
    
    /**
     * Refresh the index reader and searcher
     */
    /**
     * Refresh the IndexReader to see latest changes.
     * This should be called after any write operations.
     * Synchronized to prevent concurrent refreshes that could cause AlreadyClosedException.
     */
    private synchronized void refreshReader() {
        try {
            IndexReader oldReader = indexReader;
            
            // Check if index exists before trying to open it
            if (DirectoryReader.indexExists(directory)) {
                indexReader = DirectoryReader.open(directory);
            } else if (indexWriter != null) {
                indexWriter.commit();
                indexReader = DirectoryReader.open(indexWriter);
            } else {
                indexReader = DirectoryReader.open(directory);
            }
            indexSearcher = new IndexSearcher(indexReader);
            
            // Close old reader after new one is created to minimize race conditions
            if (oldReader != null) {
                try {
                    oldReader.close();
                } catch (IOException e) {
                    log.warn("Error closing old IndexReader during refresh", e);
                }
            }
            
        } catch (Exception e) {
            log.error("Error refreshing Lucene reader", e);
            throw new AIServiceException("Failed to refresh Lucene reader", e);
        }
    }

    private IndexWriter recoverFromLock(Directory directory, IndexWriterConfig config, Path path) {
        final long timeoutMillis = 5000L;
        final long retryDelayMillis = 100L;
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (System.currentTimeMillis() < deadline) {
            try {
                Lock cleanupLock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME);
                try {
                    cleanupLock.close();
                } catch (IOException closeException) {
                    log.warn("Error closing Lucene cleanup lock for {}", path, closeException);
                }
            } catch (LockObtainFailedException retryException) {
                log.warn("Lucene lock at {} still held; retrying in {} ms", path, retryDelayMillis);
                sleep(retryDelayMillis);
                continue;
            } catch (IOException ioException) {
                throw new UncheckedIOException(ioException);
            }

            try {
                return new IndexWriter(directory, config);
            } catch (LockObtainFailedException retryException) {
                log.warn("Unable to reopen Lucene index at {}; retrying in {} ms", path, retryDelayMillis, retryException);
                sleep(retryDelayMillis);
            } catch (IOException ioException) {
                throw new UncheckedIOException(ioException);
            }
        }

        throw new UncheckedIOException(new LockObtainFailedException("Unable to recover Lucene lock at " + path));
    }

    private void closeSharedIndex(SharedIndex shared, Path path) {
        try {
            shared.writer.close();
        } catch (IOException e) {
            log.warn("Error closing IndexWriter for {}", path, e);
        }
        try {
            shared.directory.close();
        } catch (IOException e) {
            log.warn("Error closing Directory for {}", path, e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new AIServiceException("Interrupted while waiting for Lucene lock recovery", interruptedException);
        }
    }

    private static class SharedIndex {
        private final Directory directory;
        private final IndexWriter writer;
        private final AtomicInteger refCount = new AtomicInteger(1);

        private SharedIndex(Directory directory, IndexWriter writer) {
            this.directory = directory;
            this.writer = writer;
        }

        private void retain() {
            refCount.incrementAndGet();
        }

        private boolean release() {
            return refCount.decrementAndGet() == 0;
        }
    }

    private void deleteDirectoryRecursively(Path directoryPath) {
        try {
            if (!Files.exists(directoryPath)) {
                return;
            }
            if (!Files.isDirectory(directoryPath)) {
                return;
            }

            Files.walk(directoryPath)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        log.debug("Unable to delete {}", path, e);
                    }
                });
        } catch (IOException e) {
            log.debug("Unable to cleanup Lucene index directory {}", directoryPath, e);
        }
    }
}
