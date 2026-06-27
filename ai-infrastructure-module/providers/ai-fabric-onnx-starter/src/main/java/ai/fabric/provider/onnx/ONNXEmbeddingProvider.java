package ai.fabric.provider.onnx;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.exception.AIServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.lang.reflect.Method;

/**
 * ONNX Runtime Embedding Provider
 * 
 * Provides local embedding generation using ONNX Runtime.
 * No external API calls required - runs entirely on local machine.
 * 
 * Requires:
 * - ONNX model file (.onnx)
 * - Tokenizer file (tokenizer.json or Java-based tokenizer)
 * 
 * Default provider when ai.embedding.provider=onnx (or not specified)
 * 
 * @author AI Infrastructure Team
 * @version 2.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class ONNXEmbeddingProvider implements EmbeddingProvider {

    private final AIProviderConfig config;

    private String modelPath;
    private String tokenizerPath;
    private int maxSequenceLength;
    private boolean useGpu;
    
    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;
    private int embeddingDimension = 384; // Default for all-MiniLM-L6-v2
    private Path resolvedModelPath;
    private byte[] resolvedModelBytes;
    private Path resolvedTokenizerPath;
    private boolean tokenizerReady = false;
    private Object tokenizerInstance;
    private Method tokenizerEncodeMethod;
    private Method encodingGetIdsMethod;
    private Method encodingGetAttentionMaskMethod;
    private Method encodingGetTypeIdsMethod;
    private boolean tokenizerEncodeSupportsAddSpecialTokens = false;
    
    // Thread safety: ONNX Runtime sessions are NOT thread-safe
    // Using ReentrantLock to synchronize access to ortSession
    private final ReentrantLock sessionLock = new ReentrantLock();
    
    // Special token IDs for BERT-based models (all-MiniLM-L6-v2)
    private static final int TOKEN_CLS = 101;  // [CLS] token
    private static final int TOKEN_SEP = 102;  // [SEP] token
    private static final int TOKEN_PAD = 0;    // [PAD] token
    private static final int TOKEN_UNK = 100;  // [UNK] token
    private static final int VOCAB_SIZE = 30522; // BERT vocabulary size
    private static final ConcurrentMap<String, Path> CLASSPATH_RESOURCE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, byte[]> CLASSPATH_RESOURCE_BYTES_CACHE = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void initialize() {
        try {
            AIProviderConfig.ONNXConfig onnx = config.getOnnx();
            if (onnx == null) {
                log.error("ONNX configuration is missing. Provider will not be available.");
                return;
            }

            modelPath = onnx.getModelPath();
            tokenizerPath = onnx.getTokenizerPath();
            maxSequenceLength = onnx.getMaxSequenceLength() != null ? onnx.getMaxSequenceLength() : 512;
            useGpu = onnx.getUseGpu() != null && onnx.getUseGpu();

            log.info("========================================");
            log.info("Initializing ONNX Embedding Provider");
            log.info("Model path: {}", modelPath);
            log.info("Tokenizer path: {}", tokenizerPath);
            log.info("Current working directory: {}", System.getProperty("user.dir"));
            log.info("========================================");

            // Model resolution (avoid temp-file extraction for classpath models to prevent filling disk during tests).
            if (modelPath != null && modelPath.startsWith("classpath:")) {
                String resourcePath = modelPath.substring("classpath:".length());
                if (resourcePath.startsWith("/")) {
                    resourcePath = resourcePath.substring(1);
                }

                byte[] cached = CLASSPATH_RESOURCE_BYTES_CACHE.get(resourcePath);
                if (cached == null) {
                    try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                        if (inputStream == null) {
                            log.error("========================================");
                            log.error("ONNX model classpath resource not found (requested='{}')", modelPath);
                            log.error("Provider will not be available.");
                            log.error("========================================");
                            return;
                        }
                        byte[] bytes = inputStream.readAllBytes();
                        byte[] existing = CLASSPATH_RESOURCE_BYTES_CACHE.putIfAbsent(resourcePath, bytes);
                        cached = existing != null ? existing : bytes;
                    }
                }

                resolvedModelBytes = cached;
                resolvedModelPath = null;
                log.info("Model loaded from classpath resource: {} ({} bytes)", resourcePath, cached != null ? cached.length : 0);
            } else {
                resolvedModelPath = resolvePath(modelPath, "model").orElse(null);
                if (resolvedModelPath == null || !Files.exists(resolvedModelPath)) {
                    log.error("========================================");
                    log.error("ONNX model file not found (requested='{}', resolved='{}')", modelPath, resolvedModelPath);
                    log.error("Provider will not be available.");
                    log.error("========================================");
                    return;
                }

                log.info("Model file resolved to: {}", resolvedModelPath.toAbsolutePath());
            }

            ortEnvironment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();

            if (useGpu) {
                try {
                    sessionOptions.addCUDA(0);
                    log.info("Using GPU for ONNX inference");
                } catch (Exception e) {
                    log.warn("GPU not available, falling back to CPU: {}", e.getMessage());
                    sessionOptions.addCPU(true);
                }
            } else {
                sessionOptions.addCPU(true);
                log.info("Using CPU for ONNX inference");
            }

            if (resolvedModelBytes != null && resolvedModelBytes.length > 0) {
                ortSession = ortEnvironment.createSession(resolvedModelBytes, sessionOptions);
            } else {
                ortSession = ortEnvironment.createSession(resolvedModelPath.toString(), sessionOptions);
            }

            Map<String, NodeInfo> inputInfo = ortSession.getInputInfo();
            log.info("Model input names: {}", inputInfo.keySet());
            for (Map.Entry<String, NodeInfo> entry : inputInfo.entrySet()) {
                log.info("  Input: {} - {}", entry.getKey(), entry.getValue().getInfo().toString());
            }

            Map<String, NodeInfo> outputInfo = ortSession.getOutputInfo();
            for (Map.Entry<String, NodeInfo> entry : outputInfo.entrySet()) {
                NodeInfo nodeInfo = entry.getValue();
                if (nodeInfo.getInfo() instanceof TensorInfo) {
                    TensorInfo tensorInfo = (TensorInfo) nodeInfo.getInfo();
                    long[] shape = tensorInfo.getShape();
                    if (shape.length >= 2) {
                        embeddingDimension = (int) shape[shape.length - 1];
                        log.info("Detected embedding dimension: {}", embeddingDimension);
                    }
                }
            }

            resolvedTokenizerPath = resolvePath(tokenizerPath, "tokenizer").orElse(null);
            initializeTokenizer();

            log.info("ONNX Embedding Provider initialized successfully with model: {}", resolvedModelPath);

        } catch (Exception e) {
            log.error("Failed to initialize ONNX Embedding Provider", e);
            throw new AIServiceException("Failed to initialize ONNX Embedding Provider", e);
        }
    }
    
    @PreDestroy
    public void cleanup() {
        try {
            if (ortSession != null) {
                ortSession.close();
            }
            // Note: OrtEnvironment is a singleton and should not be closed
            log.debug("ONNX Embedding Provider cleaned up");
        } catch (Exception e) {
            log.error("Error cleaning up ONNX Embedding Provider", e);
        }
    }

    private void initializeTokenizer() {
        tokenizerReady = false;
        tokenizerInstance = null;
        tokenizerEncodeMethod = null;
        encodingGetIdsMethod = null;
        encodingGetAttentionMaskMethod = null;
        encodingGetTypeIdsMethod = null;
        tokenizerEncodeSupportsAddSpecialTokens = false;
        if (resolvedTokenizerPath == null) {
            log.warn("Tokenizer file not configured or could not be resolved. Falling back to legacy tokenization.");
            return;
        }
        try {
            Path tokenizerPathToUse = Files.exists(resolvedTokenizerPath)
                ? resolvedTokenizerPath
                : resolvedTokenizerPath.toAbsolutePath();
            if (!Files.exists(tokenizerPathToUse)) {
                log.warn("Tokenizer file not found at: {}. Falling back to legacy tokenization.", tokenizerPathToUse);
                return;
            }

            Class<?> tokenizerClass = Class.forName("com.huggingface.tokenizers.Tokenizer");
            Class<?> encodingClass = Class.forName("com.huggingface.tokenizers.Encoding");

            Method fromFileMethod = tokenizerClass.getMethod("fromFile", String.class);
            Object instance = fromFileMethod.invoke(null, tokenizerPathToUse.toString());

            Method encodeMethod;
            boolean supportsAddSpecialTokens = false;
            try {
                encodeMethod = tokenizerClass.getMethod("encode", String.class, boolean.class);
                supportsAddSpecialTokens = true;
            } catch (NoSuchMethodException e) {
                encodeMethod = tokenizerClass.getMethod("encode", String.class);
                supportsAddSpecialTokens = false;
            }

            Method getIdsMethod = encodingClass.getMethod("getIds");
            Method getAttentionMaskMethod = encodingClass.getMethod("getAttentionMask");
            Method getTypeIdsMethod = encodingClass.getMethod("getTypeIds");

            tokenizerInstance = instance;
            tokenizerEncodeMethod = encodeMethod;
            encodingGetIdsMethod = getIdsMethod;
            encodingGetAttentionMaskMethod = getAttentionMaskMethod;
            encodingGetTypeIdsMethod = getTypeIdsMethod;
            tokenizerEncodeSupportsAddSpecialTokens = supportsAddSpecialTokens;
            resolvedTokenizerPath = tokenizerPathToUse;
            tokenizerReady = true;
            log.info("Tokenizer initialized via Hugging Face tokenizers at {}", tokenizerPathToUse);
        } catch (ClassNotFoundException e) {
            log.warn("Hugging Face tokenizers library not found on classpath. Falling back to legacy tokenization.");
        } catch (NoSuchMethodException e) {
            log.error("Hugging Face tokenizers library detected but methods not found. Falling back to legacy tokenization.", e);
        } catch (Exception ex) {
            log.error("Failed to initialize tokenizer from {}. Falling back to legacy tokenization.", resolvedTokenizerPath, ex);
        }
    }

    private Optional<Path> resolvePath(String configuredPath, String descriptor) throws IOException {
        if (configuredPath == null || configuredPath.isBlank()) {
            return Optional.empty();
        }

        if (configuredPath.startsWith("classpath:")) {
            String resourcePath = configuredPath.substring("classpath:".length());
            if (resourcePath.startsWith("/")) {
                resourcePath = resourcePath.substring(1);
            }

            Path cached = CLASSPATH_RESOURCE_CACHE.get(resourcePath);
            if (cached != null && Files.exists(cached)) {
                return Optional.of(cached);
            }

            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    log.warn("Classpath resource '{}' not found for {}", resourcePath, descriptor);
                    return Optional.empty();
                }
                String suffix = resourcePath.contains(".") ? resourcePath.substring(resourcePath.lastIndexOf('.')) : "";
                Path tempFile = Files.createTempFile("onnx-" + descriptor + "-", suffix);
                try {
                    Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                        // Best-effort cleanup for partially written temp files.
                    }
                    throw ex;
                }

                tempFile.toFile().deleteOnExit();

                Path existing = CLASSPATH_RESOURCE_CACHE.putIfAbsent(resourcePath, tempFile);
                if (existing != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                        // Another thread won the race; keep deleteOnExit as fallback.
                    }
                    return Optional.of(existing);
                }

                return Optional.of(tempFile);
            }
        }

        try {
            Path candidate = Paths.get(configuredPath);
            if (!candidate.isAbsolute()) {
                Path absolute = Paths.get(System.getProperty("user.dir")).resolve(candidate).normalize();
                return Optional.of(absolute);
            }
            return Optional.of(candidate.normalize());
        } catch (InvalidPathException ex) {
            log.warn("Invalid path '{}' for {}: {}", configuredPath, descriptor, ex.getMessage());
            return Optional.empty();
        }
    }
    
    
    @Override
    public String getProviderName() {
        return "onnx";
    }
    
    @Override
    public boolean isAvailable() {
        return ortSession != null && ortEnvironment != null;
    }
    
    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        String text = requireEmbeddingText(request);
        sessionLock.lock();
        try {
            if (!isAvailable()) {
                throw new AIServiceException("ONNX Embedding Provider is not available");
            }
            
            log.debug("Generating embedding using ONNX for text: {}", text);
            
            long startTime = System.currentTimeMillis();
            
            TokenizationResult tokenization = tokenizeText(text);
            long[] inputIds = tokenization.getInputIds();
            long[] attentionMask = tokenization.getAttentionMask();
            long[] tokenTypeIds = tokenization.getTokenTypeIds();

            long[] shape = new long[]{1, maxSequenceLength};
            OnnxTensor inputIdsTensor = null;
            OnnxTensor attentionMaskTensor = null;
            OnnxTensor tokenTypeIdsTensor = null;
            try {
                inputIdsTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(inputIds), shape);
                attentionMaskTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(attentionMask), shape);
                tokenTypeIdsTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(tokenTypeIds), shape);

                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", inputIdsTensor);
                inputs.put("attention_mask", attentionMaskTensor);
                inputs.put("token_type_ids", tokenTypeIdsTensor);

                OrtSession.Result output = ortSession.run(inputs);
                try {
                    OnnxValue embeddingValue = output.get(0);
                    float[][] embeddings;
                    try {
                        embeddings = extractBatchEmbeddings(embeddingValue, Collections.singletonList(tokenization));
                    } catch (OrtException ex) {
                        throw new AIServiceException("Failed to extract ONNX embedding output", ex);
                    }
                    float[] embeddingVector = embeddings[0];

                    List<Double> embedding = IntStream.range(0, embeddingVector.length)
                        .mapToObj(i -> (double) embeddingVector[i])
                        .collect(Collectors.toList());

                    long processingTime = System.currentTimeMillis() - startTime;

                    log.debug("Successfully generated ONNX embedding with {} dimensions in {}ms",
                            embedding.size(), processingTime);

                    return AIEmbeddingResponse.builder()
                        .embedding(embedding)
                        .model("onnx:" + resolveModelName())
                        .dimensions(embedding.size())
                        .processingTimeMs(processingTime)
                        .requestId(UUID.randomUUID().toString())
                        .build();
                } finally {
                    output.close();
                }
            } finally {
                if (inputIdsTensor != null) {
                    inputIdsTensor.close();
                }
                if (attentionMaskTensor != null) {
                    attentionMaskTensor.close();
                }
                if (tokenTypeIdsTensor != null) {
                    tokenTypeIdsTensor.close();
                }
            }
            
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating ONNX embedding", e);
            throw new AIServiceException("Failed to generate ONNX embedding", e);
        } finally {
            sessionLock.unlock();
        }
    }
    
    @Override
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> validatedTexts = requireEmbeddingTexts(texts);
        
        if (validatedTexts.size() == 1) {
            // Single item - use single embedding method
            AIEmbeddingRequest request = AIEmbeddingRequest.builder()
                .text(validatedTexts.getFirst())
                .model("onnx")
                .build();
            return Collections.singletonList(generateEmbedding(request));
        }
        
        // True batch processing: single ONNX inference call
        sessionLock.lock();
        try {
            if (!isAvailable()) {
                throw new AIServiceException("ONNX Embedding Provider is not available");
            }
            
            log.debug("Generating {} embeddings using ONNX batch processing", validatedTexts.size());
            
            long startTime = System.currentTimeMillis();
            
            List<TokenizationResult> tokenizations = new ArrayList<>(validatedTexts.size());
            for (String text : validatedTexts) {
                tokenizations.add(tokenizeText(text));
            }

            int batchSize = tokenizations.size();
            int sequenceLength = this.maxSequenceLength;

            long[] flatInputIds = new long[batchSize * sequenceLength];
            long[] flatAttentionMasks = new long[batchSize * sequenceLength];
            long[] flatTokenTypeIds = new long[batchSize * sequenceLength];

            for (int b = 0; b < batchSize; b++) {
                TokenizationResult tokenization = tokenizations.get(b);
                int offset = b * sequenceLength;
                System.arraycopy(tokenization.getInputIds(), 0, flatInputIds, offset, sequenceLength);
                System.arraycopy(tokenization.getAttentionMask(), 0, flatAttentionMasks, offset, sequenceLength);
                System.arraycopy(tokenization.getTokenTypeIds(), 0, flatTokenTypeIds, offset, sequenceLength);
            }

            long[] batchShape = new long[]{batchSize, sequenceLength};
            LongBuffer inputIdsBuffer = LongBuffer.wrap(flatInputIds);
            LongBuffer attentionMaskBuffer = LongBuffer.wrap(flatAttentionMasks);
            LongBuffer tokenTypeIdsBuffer = LongBuffer.wrap(flatTokenTypeIds);

            OnnxTensor inputIdsTensor = null;
            OnnxTensor attentionMaskTensor = null;
            OnnxTensor tokenTypeIdsTensor = null;
            
            try {
                inputIdsTensor = OnnxTensor.createTensor(ortEnvironment, inputIdsBuffer, batchShape);
                attentionMaskTensor = OnnxTensor.createTensor(ortEnvironment, attentionMaskBuffer, batchShape);
                tokenTypeIdsTensor = OnnxTensor.createTensor(ortEnvironment, tokenTypeIdsBuffer, batchShape);
                
                // Prepare batch inputs
                Map<String, OnnxTensor> batchInputs = new HashMap<>();
                batchInputs.put("input_ids", inputIdsTensor);
                batchInputs.put("attention_mask", attentionMaskTensor);
                batchInputs.put("token_type_ids", tokenTypeIdsTensor);
                
                float[][] batchEmbeddings;
                OrtSession.Result batchOutput = ortSession.run(batchInputs);
                try {
                    // Extract batch embeddings
                    OnnxValue embeddingValue = batchOutput.get(0);
                    try {
                        batchEmbeddings = extractBatchEmbeddings(embeddingValue, tokenizations);
                    } catch (OrtException ex) {
                        throw new AIServiceException("Failed to extract ONNX batch embedding output", ex);
                    }
                } finally {
                    batchOutput.close();
                }
                
                // Convert to responses
                List<AIEmbeddingResponse> responses = new ArrayList<>(batchSize);
                long processingTime = System.currentTimeMillis() - startTime;
                
                for (int i = 0; i < batchSize; i++) {
                    final float[] embeddingArray = batchEmbeddings[i]; // Make final for lambda
                    List<Double> embedding = IntStream.range(0, embeddingArray.length)
                        .mapToObj(j -> (double) embeddingArray[j])
                        .collect(Collectors.toList());
                    
                    responses.add(AIEmbeddingResponse.builder()
                        .embedding(embedding)
                        .model("onnx:" + resolveModelName())
                        .dimensions(embedding.size())
                        .processingTimeMs(processingTime / batchSize)
                        .requestId(UUID.randomUUID().toString())
                        .build());
                }
                log.debug("Successfully generated {} ONNX embeddings in batch in {}ms (avg {}ms per embedding)", 
                         batchSize, processingTime, processingTime / batchSize);
                
                return List.copyOf(responses);
                
            } finally {
                // Cleanup tensors
                if (inputIdsTensor != null) inputIdsTensor.close();
                if (attentionMaskTensor != null) attentionMaskTensor.close();
                if (tokenTypeIdsTensor != null) tokenTypeIdsTensor.close();
            }
            
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating batch ONNX embeddings", e);
            throw new AIServiceException("Failed to generate batch ONNX embeddings", e);
        } finally {
            sessionLock.unlock();
        }
    }
    
    /**
     * Extract batch embeddings from ONNX output
     * Handles 2D, 3D, and 4D tensor outputs
     */
    private float[][] extractBatchEmbeddings(OnnxValue value, List<TokenizationResult> tokenizations) throws OrtException {
        Object rawValue = value.getValue();
        return extractBatchEmbeddings(rawValue, tokenizations);
    }

    private float[][] extractBatchEmbeddings(Object rawValue, List<TokenizationResult> tokenizations) {
        int expectedBatchSize = tokenizations.size();
        float[][] embeddings;

        if (rawValue instanceof float[][][]) {
            float[][][] tensorValue3D = (float[][][]) rawValue;
            if (tensorValue3D.length == 0 || tensorValue3D[0].length == 0) {
                throw new AIServiceException("Empty 3D array output");
            }
            if (tensorValue3D.length != expectedBatchSize) {
                throw new AIServiceException(
                    String.format("Batch size mismatch: expected %d, got %d", expectedBatchSize, tensorValue3D.length));
            }
            int batchSize = tensorValue3D.length;
            int embeddingDim = tensorValue3D[0][0].length;
            embeddings = new float[batchSize][embeddingDim];
            for (int b = 0; b < batchSize; b++) {
                embeddings[b] = meanPoolEmbeddings(tensorValue3D[b], tokenizations.get(b));
            }
            log.debug("Extracted batch embeddings from 3D tensor [batch={}, sequence={}, embedding={}]",
                    batchSize, tensorValue3D[0].length, embeddingDim);
        } else if (rawValue instanceof float[][][][]) {
            float[][][][] tensorValue4D = (float[][][][]) rawValue;
            if (tensorValue4D.length == 0 || tensorValue4D[0].length == 0) {
                throw new AIServiceException("Empty 4D tensor output");
            }
            if (tensorValue4D.length != expectedBatchSize) {
                throw new AIServiceException(
                    String.format("Batch size mismatch: expected %d, got %d", expectedBatchSize, tensorValue4D.length));
            }
            int batchSize = tensorValue4D.length;
            int seqLength = tensorValue4D[0].length;
            int embeddingDim = tensorValue4D[0][0][0].length;
            embeddings = new float[batchSize][embeddingDim];
            for (int b = 0; b < batchSize; b++) {
                float[][] flattened = new float[seqLength][embeddingDim];
                for (int s = 0; s < seqLength; s++) {
                    flattened[s] = tensorValue4D[b][s][0];
                }
                embeddings[b] = meanPoolEmbeddings(flattened, tokenizations.get(b));
            }
            log.debug("Extracted batch embeddings from 4D tensor [batch={}, sequence={}, embedding={}]",
                    batchSize, seqLength, embeddingDim);
        } else if (rawValue instanceof float[][]) {
            embeddings = (float[][]) rawValue;
            if (embeddings.length != expectedBatchSize) {
                throw new AIServiceException(
                    String.format("Batch size mismatch: expected %d, got %d", expectedBatchSize, embeddings.length));
            }
            log.debug("Extracted batch embeddings from 2D tensor [batch={}, embedding={}]",
                    embeddings.length, embeddings.length > 0 ? embeddings[0].length : 0);
        } else if (rawValue instanceof float[]) {
            float[] flat = (float[]) rawValue;
            if (flat.length % expectedBatchSize != 0) {
                throw new AIServiceException(String.format(
                    "Flat tensor length %d is not divisible by batch size %d", flat.length, expectedBatchSize));
            }
            int embeddingDim = flat.length / expectedBatchSize;
            embeddings = new float[expectedBatchSize][embeddingDim];
            for (int b = 0; b < expectedBatchSize; b++) {
                System.arraycopy(flat, b * embeddingDim, embeddings[b], 0, embeddingDim);
            }
            log.debug("Reshaped 1D array to batch embeddings [batch={}, embedding={}]",
                    expectedBatchSize, embeddingDim);
        } else if (rawValue instanceof OnnxTensor) {
            OnnxTensor tensor = (OnnxTensor) rawValue;
            try {
                return extractBatchEmbeddings(tensor.getValue(), tokenizations);
            } catch (OrtException e) {
                throw new AIServiceException("Failed to extract tensor value", e);
            } finally {
                try {
                    tensor.close();
                } catch (Exception e) {
                    log.warn("Error closing OnnxTensor", e);
                }
            }
        } else {
            throw new AIServiceException("Unexpected batch embedding output format: " +
                (rawValue != null ? rawValue.getClass().getName() : "null"));
        }

        return embeddings;
    }

    private float[] meanPoolEmbeddings(float[][] tokenEmbeddings, TokenizationResult tokenization) {
        if (tokenEmbeddings == null || tokenEmbeddings.length == 0) {
            return new float[embeddingDimension];
        }
        int embeddingDim = tokenEmbeddings[0].length;
        float[] pooled = new float[embeddingDim];
        long[] mask = tokenization.getAttentionMask();
        int validTokenCount = tokenization.getValidTokenCount();

        float divisor = 0f;
        for (int s = 0; s < tokenEmbeddings.length; s++) {
            boolean include = false;
            if (mask != null && s < mask.length) {
                include = mask[s] > 0;
            } else if (s < validTokenCount) {
                include = true;
            }
            if (!include) {
                continue;
            }
            float[] tokenVector = tokenEmbeddings[s];
            for (int e = 0; e < embeddingDim; e++) {
                pooled[e] += tokenVector[e];
            }
            divisor += 1f;
        }

        if (divisor == 0f) {
            divisor = Math.max(1, Math.min(validTokenCount, tokenEmbeddings.length));
        }
        if (divisor == 0f) {
            divisor = tokenEmbeddings.length;
        }

        for (int e = 0; e < pooled.length; e++) {
            pooled[e] /= divisor;
        }

        return pooled;
    }
    
    @Override
    public int getEmbeddingDimension() {
        return embeddingDimension;
    }
    
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("provider", "onnx");
        status.put("available", isAvailable());
        status.put("modelPath", modelPath);
        status.put("embeddingDimension", embeddingDimension);
        status.put("maxSequenceLength", maxSequenceLength);
        status.put("useGpu", useGpu);
        
        if (isAvailable()) {
            status.put("status", "ready");
        } else {
            status.put("status", "not_initialized");
            status.put("message", "Model file not found or provider not initialized");
        }
        
        return status;
    }
    
    private TokenizationResult tokenizeText(String text) {
        if (tokenizerReady && tokenizerInstance != null && tokenizerEncodeMethod != null) {
            try {
                return tokenizeWithTokenizer(text);
            } catch (Exception ex) {
                log.warn("Tokenizer failed to encode text. Falling back to legacy tokenization: {}", ex.getMessage());
                tokenizerReady = false;
            }
        }
        return fallbackTokenize(text);
    }

    private String requireEmbeddingText(AIEmbeddingRequest request) {
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            throw new IllegalArgumentException("Embedding text cannot be blank");
        }
        if (request.getText().length() > 8000) {
            throw new IllegalArgumentException("Embedding text cannot exceed 8000 characters");
        }
        return request.getText();
    }

    private List<String> requireEmbeddingTexts(List<String> texts) {
        List<String> validated = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Embedding text at index " + i + " cannot be blank");
            }
            if (text.length() > 8000) {
                throw new IllegalArgumentException("Embedding text at index " + i + " cannot exceed 8000 characters");
            }
            validated.add(text);
        }
        return List.copyOf(validated);
    }

    private TokenizationResult tokenizeWithTokenizer(String text) throws Exception {
        String safeText = text == null ? "" : text;
        Object encoding;
        if (tokenizerEncodeSupportsAddSpecialTokens) {
            encoding = tokenizerEncodeMethod.invoke(tokenizerInstance, safeText, Boolean.TRUE);
        } else {
            encoding = tokenizerEncodeMethod.invoke(tokenizerInstance, safeText);
        }

        long[] ids = (long[]) encodingGetIdsMethod.invoke(encoding);
        long[] attention = encodingGetAttentionMaskMethod != null
            ? (long[]) encodingGetAttentionMaskMethod.invoke(encoding)
            : null;
        long[] typeIds = encodingGetTypeIdsMethod != null
            ? (long[]) encodingGetTypeIdsMethod.invoke(encoding)
            : null;

        long[] inputIds = new long[maxSequenceLength];
        long[] attentionMask = new long[maxSequenceLength];
        long[] tokenTypeIds = new long[maxSequenceLength];

        int length = Math.min(ids.length, maxSequenceLength);
        if (ids.length > 0) {
            System.arraycopy(ids, 0, inputIds, 0, length);
        }

        if (attention != null && attention.length >= length) {
            System.arraycopy(attention, 0, attentionMask, 0, length);
        } else {
            Arrays.fill(attentionMask, 0, length, 1L);
        }

        if (typeIds != null && typeIds.length >= length) {
            System.arraycopy(typeIds, 0, tokenTypeIds, 0, length);
        }

        int validTokenCount = 0;
        for (int i = 0; i < length; i++) {
            if (attentionMask[i] > 0) {
                validTokenCount++;
            }
        }
        if (validTokenCount == 0) {
            validTokenCount = Math.max(1, length);
        }

        return new TokenizationResult(inputIds, attentionMask, tokenTypeIds, validTokenCount);
    }

    private TokenizationResult fallbackTokenize(String text) {
        int[] legacyTokens = legacyTokenizeToInts(text);
        long[] inputIds = new long[maxSequenceLength];
        long[] attentionMask = new long[maxSequenceLength];
        long[] tokenTypeIds = new long[maxSequenceLength];
        int validTokenCount = 0;

        for (int i = 0; i < maxSequenceLength; i++) {
            int token = i < legacyTokens.length ? legacyTokens[i] : TOKEN_PAD;
            inputIds[i] = token;
            if (token != TOKEN_PAD) {
                attentionMask[i] = 1L;
                validTokenCount++;
            }
        }

        if (validTokenCount == 0) {
            attentionMask[0] = 1L;
            validTokenCount = 1;
        }

        return new TokenizationResult(inputIds, attentionMask, tokenTypeIds, validTokenCount);
    }

    private int[] legacyTokenizeToInts(String text) {
        if (text == null || text.trim().isEmpty()) {
            return createPaddedTokens(new int[]{TOKEN_CLS, TOKEN_SEP}, maxSequenceLength);
        }

        String normalized = text.toLowerCase()
            .trim()
            .replaceAll("\\s+", " ")
            .replaceAll("[\\u2000-\\u206F]", " ")
            .replaceAll("[\\u00A0]", " ");

        List<String> wordTokens = tokenizeIntoWords(normalized);

        List<Integer> tokenIds = new ArrayList<>();
        tokenIds.add(TOKEN_CLS);

        for (String word : wordTokens) {
            List<Integer> wordTokenIds = wordPieceTokenize(word);
            tokenIds.addAll(wordTokenIds);

            int maxContentLength = maxSequenceLength - 2;
            if (tokenIds.size() >= maxContentLength) {
                break;
            }
        }

        tokenIds.add(TOKEN_SEP);

        int[] tokens = tokenIds.stream().mapToInt(Integer::intValue).toArray();
        return createPaddedTokens(tokens, maxSequenceLength);
    }
    
    /**
     * Tokenize text into words, handling punctuation
     */
    private List<String> tokenizeIntoWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder currentWord = new StringBuilder();
        
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                // Word character
                currentWord.append(c);
            } else {
                // Non-word character (punctuation, whitespace, etc.)
                if (currentWord.length() > 0) {
                    words.add(currentWord.toString());
                    currentWord.setLength(0);
                }
                // Treat punctuation as separate token if it's significant
                if (!Character.isWhitespace(c) && c != '\'' && c != '"') {
                    words.add(String.valueOf(c));
                }
            }
        }
        
        // Add remaining word
        if (currentWord.length() > 0) {
            words.add(currentWord.toString());
        }
        
        return words;
    }
    
    /**
     * WordPiece tokenization: tries full word first, then splits into subwords
     * This is a simplified approximation of WordPiece algorithm
     */
    private List<Integer> wordPieceTokenize(String word) {
        List<Integer> tokenIds = new ArrayList<>();
        
        // Try full word first (hash-based vocabulary lookup)
        int fullWordId = wordToTokenId(word);
        if (fullWordId != TOKEN_UNK) {
            // Word exists in vocabulary (approximation)
            tokenIds.add(fullWordId);
            return tokenIds;
        }
        
        // Word not found - split into subwords using WordPiece algorithm
        // Try to split on common prefixes/suffixes
        String remaining = word;
        int attempts = 0;
        int maxAttempts = 20; // Prevent infinite loops
        
        while (!remaining.isEmpty() && attempts < maxAttempts) {
            attempts++;
            
            // Try longest match starting from the beginning
            String longestMatch = findLongestSubword(remaining);
            if (longestMatch != null && !longestMatch.isEmpty()) {
                int tokenId = wordToTokenId(longestMatch);
                tokenIds.add(tokenId);
                remaining = remaining.substring(longestMatch.length());
            } else {
                // No match found - use character-based fallback
                // Add ## prefix for subword tokens (WordPiece convention)
                for (char c : remaining.toCharArray()) {
                    String subword = "##" + c;
                    int tokenId = wordToTokenId(subword);
                    tokenIds.add(tokenId != TOKEN_UNK ? tokenId : wordToTokenId(String.valueOf(c)));
                }
                break;
            }
        }
        
        // If still couldn't tokenize, use UNK token
        if (tokenIds.isEmpty()) {
            tokenIds.add(TOKEN_UNK);
        }
        
        return tokenIds;
    }
    
    /**
     * Find longest subword match (simplified approximation)
     */
    private String findLongestSubword(String word) {
        // Try progressively shorter prefixes
        for (int len = Math.min(word.length(), 20); len > 0; len--) {
            String prefix = word.substring(0, len);
            int tokenId = wordToTokenId(prefix);
            if (tokenId != TOKEN_UNK) {
                return prefix;
            }
        }
        return "";
    }
    
    /**
     * Convert word/subword to token ID using hash-based vocabulary
     * This is an approximation - real WordPiece uses a vocabulary lookup table
     */
    private int wordToTokenId(String word) {
        if (word == null || word.isEmpty()) {
            return TOKEN_PAD;
        }
        
        // Use deterministic hash to map words to token IDs
        // This simulates vocabulary lookup (real vocab would have ~30k entries)
        long hash = word.hashCode();
        
        // Map hash to vocabulary range [100, 30521]
        // Reserve IDs 0-100 for special tokens
        int tokenId = (int) (Math.abs(hash) % (VOCAB_SIZE - 100)) + 100;
        
        // Ensure within valid range
        if (tokenId < 100 || tokenId >= VOCAB_SIZE) {
            tokenId = TOKEN_UNK;
        }
        
        return tokenId;
    }
    
    /**
     * Pad or truncate tokens to maxSequenceLength
     */
    private int[] createPaddedTokens(int[] tokens, int maxLength) {
        if (tokens.length == maxLength) {
            return tokens;
        } else if (tokens.length < maxLength) {
            // Pad with PAD tokens
            int[] padded = new int[maxLength];
            System.arraycopy(tokens, 0, padded, 0, tokens.length);
            Arrays.fill(padded, tokens.length, maxLength, TOKEN_PAD);
            return padded;
        } else {
            // Truncate - keep [CLS] and [SEP], truncate middle
            int[] truncated = new int[maxLength];
            truncated[0] = tokens[0]; // Keep [CLS]
            int contentLength = maxLength - 2; // Space for [CLS] and [SEP]
            System.arraycopy(tokens, 1, truncated, 1, contentLength);
            truncated[maxLength - 1] = TOKEN_SEP; // Add [SEP] at end
            return truncated;
        }
    }

    private String resolveModelName() {
        try {
            if (resolvedModelPath != null) {
                return resolvedModelPath.getFileName().toString();
            }
            return Paths.get(modelPath).getFileName().toString();
        } catch (Exception ex) {
            return modelPath;
        }
    }

    private static final class TokenizationResult {
        private final long[] inputIds;
        private final long[] attentionMask;
        private final long[] tokenTypeIds;
        private final int validTokenCount;

        private TokenizationResult(long[] inputIds, long[] attentionMask, long[] tokenTypeIds, int validTokenCount) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
            this.validTokenCount = validTokenCount;
        }

        long[] getInputIds() {
            return inputIds;
        }

        long[] getAttentionMask() {
            return attentionMask;
        }

        long[] getTokenTypeIds() {
            return tokenTypeIds;
        }

        int getValidTokenCount() {
            return validTokenCount;
        }
    }
}
