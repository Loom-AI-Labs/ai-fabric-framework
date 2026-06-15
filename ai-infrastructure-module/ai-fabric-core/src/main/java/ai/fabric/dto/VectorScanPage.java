package ai.fabric.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Page response for vector scan operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorScanPage {

    private List<VectorRecord> vectors;

    /**
     * Cursor to resume scanning; null when no more results.
     */
    private String nextCursor;

    private boolean hasMore;
}

