import re
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from transformers import AutoTokenizer, AutoModelForTokenClassification
import torch
import logging
from typing import List, Dict, Any, Optional

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --- Globals ---
MODEL_PATH = "./receipt_parser_model"
TOKENIZER = None
MODEL = None

# --- FastAPI App Initialization ---
app = FastAPI(
    title="Receipt Parsing API",
    description="A service to extract key information from receipt text.",
)

class ReceiptRequest(BaseModel):
    """Request model for parsing a receipt."""
    words: List[str]

class ReceiptResponse(BaseModel):
    """Response model for a parsed receipt."""
    store: Optional[str] = None
    date_time: Optional[str] = None
    total: Optional[float] = None
    items: List[Dict[str, Any]] = []

@app.on_event("startup")
def load_model():
    """Load the model and tokenizer on application startup."""
    global TOKENIZER, MODEL
    try:
        logging.info("Loading model and tokenizer...")
        TOKENIZER = AutoTokenizer.from_pretrained(MODEL_PATH)
        MODEL = AutoModelForTokenClassification.from_pretrained(MODEL_PATH)
        logging.info("Model and tokenizer loaded successfully.")
    except Exception as e:
        logging.error(f"Failed to load model: {e}")
        raise RuntimeError("Could not load the machine learning model.")

def merge_and_clean_predictions(tokens: List[str], labels: List[str]) -> Dict[str, Any]:
    """Merges BIO-tagged tokens into structured entities and cleans the data."""
    
    entities = {"STORE": [], "DATE_TIME": [], "TOTAL": [], "ITEM": []}
    current_entity = None
    current_words = []

    for token, label in zip(tokens, labels):
        bio_tag, _, entity_type = label.partition('-')
        
        if bio_tag == 'B':
            if current_entity and current_words:
                entities[current_entity].append(" ".join(current_words))
            current_entity = entity_type
            current_words = [token]
        elif bio_tag == 'I' and current_entity == entity_type:
            current_words.append(token)
        else: # 'O' tag or mismatch
            if current_entity and current_words:
                entities[current_entity].append(" ".join(current_words))
            current_entity = None
            current_words = []

    if current_entity and current_words:
        entities[current_entity].append(" ".join(current_words))

    # Post-processing
    # Most likely entity is chosen if multiple are found
    store = entities["STORE"][0] if entities["STORE"] else None
    date_time = entities["DATE_TIME"][0] if entities["DATE_TIME"] else None
    
    total = None
    if entities["TOTAL"]:
        total_str = entities["TOTAL"][0]
        # Regex to find a currency value
        match = re.search(r'(\d+\.\d{2})', total_str)
        if match:
            total = float(match.group(1))

    items = [{"name": item, "price": None} for item in entities["ITEM"]]

    return {
        "store": store,
        "date_time": date_time,
        "total": total,
        "items": items
    }


@app.post("/parse-receipt", response_model=ReceiptResponse)
def parse_receipt(request: ReceiptRequest):
    """
    Parses a list of words from a receipt to extract structured data.
    """
    if not request.words:
        raise HTTPException(status_code=400, detail="Input 'words' list cannot be empty.")

    if not TOKENIZER or not MODEL:
        raise HTTPException(status_code=503, detail="Model is not loaded.")

    try:
        # Tokenize and predict
        inputs = TOKENIZER(request.words, return_tensors="pt", is_split_into_words=True, truncation=True)
        with torch.no_grad():
            logits = MODEL(**inputs).logits
        
        predictions = torch.argmax(logits, dim=2)
        predicted_token_labels = [MODEL.config.id2label[t.item()] for t in predictions[0]]

        # Align tokens with original words
        word_ids = inputs.word_ids()
        previous_word_idx = None
        aligned_labels = []
        for i, word_idx in enumerate(word_ids):
            if word_idx is not None and word_idx != previous_word_idx:
                aligned_labels.append(predicted_token_labels[i])
            previous_word_idx = word_idx

        # Merge and clean entities
        structured_data = merge_and_clean_predictions(request.words, aligned_labels)
        
        return ReceiptResponse(**structured_data)

    except Exception as e:
        logging.error(f"Error during receipt parsing: {e}")
        raise HTTPException(status_code=500, detail="An internal error occurred during parsing.")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
