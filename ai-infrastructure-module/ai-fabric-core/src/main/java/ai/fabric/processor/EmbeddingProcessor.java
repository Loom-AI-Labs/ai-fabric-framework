package ai.fabric.processor;

import ai.fabric.config.AIProviderConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Advanced text processor for embedding generation
 * 
 * This processor handles text preprocessing, chunking, and optimization
 * for embedding generation with various strategies and configurations.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class EmbeddingProcessor {
    
    private final AIProviderConfig config;
    
    // Text processing patterns
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SENTENCE_PATTERN = Pattern.compile("[.!?]+");
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile("[^\\p{L}\\p{N}\\p{P}\\p{Z}]");
    
    // Default chunking configuration
    private static final int DEFAULT_MAX_CHUNK_SIZE = 1000;
    private static final int DEFAULT_OVERLAP_SIZE = 100;
    private static final int DEFAULT_MIN_CHUNK_SIZE = 50;
    
    /**
     * Process text for embedding generation
     * 
     * @param text the raw text to process
     * @return processed text optimized for embeddings
     */
    public String processText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        
        log.debug("Processing text of length: {}", text.length());
        
        // Step 1: Clean and normalize text
        String cleaned = cleanText(text);
        
        // Step 2: Remove excessive whitespace
        String normalized = normalizeWhitespace(cleaned);
        
        // Step 3: Truncate if too long
        String truncated = truncateText(normalized, 8000);
        
        log.debug("Processed text from {} to {} characters", text.length(), truncated.length());
        
        return truncated;
    }
    
    /**
     * Chunk text using advanced strategies
     * 
     * @param text the text to chunk
     * @param maxChunkSize maximum size of each chunk
     * @param overlapSize overlap between chunks
     * @return list of text chunks
     */
    public List<String> chunkText(String text, int maxChunkSize, int overlapSize) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        String processedText = processText(text);
        
        if (processedText.length() <= maxChunkSize) {
            return Collections.singletonList(processedText);
        }
        
        log.debug("Chunking text of length {} with max chunk size {}", processedText.length(), maxChunkSize);
        
        // Try different chunking strategies
        List<String> chunks = chunkBySentences(processedText, maxChunkSize, overlapSize);
        
        if (chunks.isEmpty()) {
            chunks = chunkByWords(processedText, maxChunkSize, overlapSize);
        }
        
        if (chunks.isEmpty()) {
            chunks = chunkByCharacters(processedText, maxChunkSize, overlapSize);
        }
        
        // Filter out chunks that are too small
        chunks = chunks.stream()
            .filter(chunk -> chunk.length() >= DEFAULT_MIN_CHUNK_SIZE)
            .collect(Collectors.toList());
        
        log.debug("Generated {} chunks from text", chunks.size());
        
        return chunks;
    }
    
    /**
     * Chunk text using default configuration
     * 
     * @param text the text to chunk
     * @return list of text chunks
     */
    public List<String> chunkText(String text) {
        return chunkText(text, DEFAULT_MAX_CHUNK_SIZE, DEFAULT_OVERLAP_SIZE);
    }
    
    /**
     * Extract key phrases from text for better embedding context
     * 
     * @param text the text to extract phrases from
     * @param maxPhrases maximum number of phrases to extract
     * @return list of key phrases
     */
    public List<String> extractKeyPhrases(String text, int maxPhrases) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        String processedText = processText(text);
        
        // Simple key phrase extraction based on word frequency
        Map<String, Integer> wordFreq = new HashMap<>();
        String[] words = processedText.toLowerCase().split("\\s+");
        
        for (String word : words) {
            if (word.length() > 3) { // Only consider words longer than 3 characters
                wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
            }
        }
        
        return wordFreq.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(maxPhrases)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate text similarity using simple metrics
     * 
     * @param text1 first text
     * @param text2 second text
     * @return similarity score between 0 and 1
     */
    public double calculateTextSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0.0;
        }
        
        String processed1 = processText(text1);
        String processed2 = processText(text2);
        
        if (processed1.equals(processed2)) {
            return 1.0;
        }
        
        // Simple Jaccard similarity
        Set<String> words1 = new HashSet<>(Arrays.asList(processed1.toLowerCase().split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(processed2.toLowerCase().split("\\s+")));
        
        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);
        
        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Clean text by removing special characters and normalizing
     */
    private String cleanText(String text) {
        // Remove special characters but keep basic punctuation
        String cleaned = SPECIAL_CHARS_PATTERN.matcher(text).replaceAll(" ");
        
        // Remove excessive punctuation
        cleaned = cleaned.replaceAll("[.]{3,}", "...");
        cleaned = cleaned.replaceAll("[!]{2,}", "!");
        cleaned = cleaned.replaceAll("[?]{2,}", "?");
        
        return cleaned.trim();
    }
    
    /**
     * Normalize whitespace in text
     */
    private String normalizeWhitespace(String text) {
        return WHITESPACE_PATTERN.matcher(text).replaceAll(" ");
    }
    
    /**
     * Truncate text to maximum length
     */
    private String truncateText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        
        // Try to truncate at sentence boundary
        String truncated = text.substring(0, maxLength);
        int lastSentenceEnd = Math.max(
            truncated.lastIndexOf('.'),
            Math.max(truncated.lastIndexOf('!'), truncated.lastIndexOf('?'))
        );
        
        if (lastSentenceEnd > maxLength * 0.8) {
            return truncated.substring(0, lastSentenceEnd + 1);
        }
        
        return truncated;
    }
    
    /**
     * Chunk text by sentences
     */
    private List<String> chunkBySentences(String text, int maxChunkSize, int overlapSize) {
        String[] sentences = SENTENCE_PATTERN.split(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() + 1 <= maxChunkSize) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(" ");
                }
                currentChunk.append(sentence.trim());
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    // Add overlap
                    String overlap = getOverlap(currentChunk.toString(), overlapSize);
                    currentChunk = new StringBuilder(overlap + " " + sentence.trim());
                } else {
                    currentChunk = new StringBuilder(sentence.trim());
                }
            }
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        return chunks;
    }
    
    /**
     * Chunk text by words
     */
    private List<String> chunkByWords(String text, int maxChunkSize, int overlapSize) {
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        
        for (String word : words) {
            if (currentChunk.length() + word.length() + 1 <= maxChunkSize) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(" ");
                }
                currentChunk.append(word);
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    // Add overlap
                    String overlap = getOverlap(currentChunk.toString(), overlapSize);
                    currentChunk = new StringBuilder(overlap + " " + word);
                } else {
                    currentChunk = new StringBuilder(word);
                }
            }
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        return chunks;
    }
    
    /**
     * Chunk text by characters (fallback method)
     */
    private List<String> chunkByCharacters(String text, int maxChunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        
        for (int i = 0; i < text.length(); i += maxChunkSize - overlapSize) {
            int end = Math.min(i + maxChunkSize, text.length());
            chunks.add(text.substring(i, end));
        }
        
        return chunks;
    }
    
    /**
     * Get overlap text from the end of a chunk
     */
    private String getOverlap(String text, int overlapSize) {
        if (text.length() <= overlapSize) {
            return text;
        }
        
        String overlap = text.substring(text.length() - overlapSize);
        int spaceIndex = overlap.indexOf(' ');
        return spaceIndex > 0 ? overlap.substring(spaceIndex + 1) : overlap;
    }
}
