#!/bin/bash

# Download ONNX Model and Tokenizer Script
# 
# This script downloads the ONNX model and tokenizer files needed
# for local embedding generation using ONNX Runtime.
#
# Default model: sentence-transformers/all-MiniLM-L6-v2
# - Embedding dimension: 384
# - Model size: ~80MB
# - Sequence length: 512 tokens
#
# Usage:
#   ./download-onnx-model.sh [model-name] [output-dir]
#
# Examples:
#   ./download-onnx-model.sh
#   ./download-onnx-model.sh all-MiniLM-L6-v2 ./models/embeddings
#   ./download-onnx-model.sh all-mpnet-base-v2 ./models/embeddings

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"

# Default values
MODEL_NAME=${1:-"all-MiniLM-L6-v2"}
OUTPUT_DIR=${2:-"${MODULE_DIR}/models/embeddings"}

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

echo "Downloading ONNX model and tokenizer..."
echo "Model: sentence-transformers/${MODEL_NAME}"
echo "Output directory: ${OUTPUT_DIR}"
echo ""

# Model file URLs
MODEL_URL="https://huggingface.co/sentence-transformers/${MODEL_NAME}/resolve/main/model.onnx"
TOKENIZER_URL="https://huggingface.co/sentence-transformers/${MODEL_NAME}/resolve/main/tokenizer.json"

# Download model file using Python (recommended method)
MODEL_FILE="${OUTPUT_DIR}/${MODEL_NAME}.onnx"
echo "Downloading model to: ${MODEL_FILE}"
echo "Using Python huggingface_hub library (recommended)..."

if command -v python3 &> /dev/null || command -v python &> /dev/null; then
    PYTHON_CMD=$(command -v python3 2>/dev/null || command -v python 2>/dev/null)
    
    # Check if huggingface_hub is available
    if $PYTHON_CMD -c "import huggingface_hub" 2>/dev/null; then
        echo "Using huggingface_hub to download model..."
        $PYTHON_CMD -c "from huggingface_hub import hf_hub_download; import os; os.makedirs('${OUTPUT_DIR}', exist_ok=True); hf_hub_download('sentence-transformers/${MODEL_NAME}', 'onnx/model.onnx', local_dir='${OUTPUT_DIR}', local_dir_use_symlinks=False)" || {
            echo "Error: Failed to download model using huggingface_hub"
            echo "Please install: pip install huggingface_hub"
            exit 1
        }
        # Rename if needed (model is downloaded to onnx/ subdirectory)
        if [ -f "${OUTPUT_DIR}/onnx/model.onnx" ]; then
            cp "${OUTPUT_DIR}/onnx/model.onnx" "$MODEL_FILE"
        elif [ -f "${OUTPUT_DIR}/model.onnx" ]; then
            cp "${OUTPUT_DIR}/model.onnx" "$MODEL_FILE"
        fi
    else
        echo "huggingface_hub not installed. Installing..."
        $PYTHON_CMD -m pip install --quiet huggingface_hub 2>/dev/null || {
            echo "Error: Failed to install huggingface_hub"
            echo "Please install manually: pip install huggingface_hub"
            exit 1
        }
        echo "Downloading model..."
        $PYTHON_CMD -c "from huggingface_hub import hf_hub_download; import os; os.makedirs('${OUTPUT_DIR}', exist_ok=True); hf_hub_download('sentence-transformers/${MODEL_NAME}', 'onnx/model.onnx', local_dir='${OUTPUT_DIR}', local_dir_use_symlinks=False)" || {
            echo "Error: Failed to download model"
            exit 1
        }
        # Rename if needed (model is downloaded to onnx/ subdirectory)
        if [ -f "${OUTPUT_DIR}/onnx/model.onnx" ]; then
            cp "${OUTPUT_DIR}/onnx/model.onnx" "$MODEL_FILE"
        elif [ -f "${OUTPUT_DIR}/model.onnx" ]; then
            cp "${OUTPUT_DIR}/model.onnx" "$MODEL_FILE"
        fi
    fi
else
    echo "Python not found. Trying direct download (may fail)..."
    if command -v wget &> /dev/null; then
        wget -q --show-progress -O "$MODEL_FILE" "$MODEL_URL" || {
            echo "Warning: Direct download failed. Please use Python method:"
            echo "  pip install huggingface_hub"
            echo "  python -c \"from huggingface_hub import hf_hub_download; hf_hub_download('sentence-transformers/${MODEL_NAME}', 'model.onnx', local_dir='${OUTPUT_DIR}')\""
            exit 1
        }
    elif command -v curl &> /dev/null; then
        curl -L --progress-bar -o "$MODEL_FILE" "$MODEL_URL" || {
            echo "Warning: Direct download failed. Please use Python method:"
            echo "  pip install huggingface_hub"
            echo "  python -c \"from huggingface_hub import hf_hub_download; hf_hub_download('sentence-transformers/${MODEL_NAME}', 'model.onnx', local_dir='${OUTPUT_DIR}')\""
            exit 1
        }
    else
        echo "Error: Neither Python nor wget/curl is available."
        echo "Please install Python and huggingface_hub: pip install huggingface_hub"
        exit 1
    fi
fi

echo "✓ Model downloaded successfully"
echo ""

# Download tokenizer file using Python (recommended)
TOKENIZER_FILE="${OUTPUT_DIR}/tokenizer.json"
echo "Downloading tokenizer to: ${TOKENIZER_FILE}"

if command -v python3 &> /dev/null || command -v python &> /dev/null; then
    PYTHON_CMD=$(command -v python3 2>/dev/null || command -v python 2>/dev/null)
    
    if $PYTHON_CMD -c "import huggingface_hub" 2>/dev/null; then
        $PYTHON_CMD -c "from huggingface_hub import hf_hub_download; hf_hub_download('sentence-transformers/${MODEL_NAME}', 'tokenizer.json', local_dir='${OUTPUT_DIR}', local_dir_use_symlinks=False)" || {
            echo "Warning: Failed to download tokenizer file (optional)"
            echo "You can still use ONNX with simple tokenization"
            exit 0
        }
    else
        echo "Warning: huggingface_hub not available. Tokenizer download skipped."
        echo "You can still use ONNX with simple tokenization"
    fi
else
    echo "Warning: Python not available. Tokenizer download skipped."
    echo "You can still use ONNX with simple tokenization"
fi

echo "✓ Tokenizer downloaded successfully"
echo ""

echo ""
echo "=========================================="
echo "Download complete!"
echo "=========================================="
echo ""
echo "Model file: ${MODEL_FILE}"
echo "Tokenizer file: ${TOKENIZER_FILE}"
echo ""
echo "Assets were downloaded to the local model directory:"
echo "  - ${OUTPUT_DIR}"
echo ""
echo "Next steps:"
echo "1. Set AI_FABRIC_ONNX_MODEL_PATH=${MODEL_FILE}"
echo "2. Set AI_FABRIC_ONNX_TOKENIZER_PATH=${TOKENIZER_FILE}"
echo "3. Restart your application or run tests."
echo ""
