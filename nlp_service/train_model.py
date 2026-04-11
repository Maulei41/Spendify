import torch
from datasets import load_from_disk
from transformers import AutoTokenizer, AutoModelForTokenClassification, TrainingArguments, Trainer, DataCollatorForTokenClassification
import numpy as np
from sklearn.metrics import accuracy_score, precision_recall_fscore_support
import logging

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

def main():
    """Main function to train the model."""
    logging.info("Starting model training...")

    # Load the processed dataset
    dataset_path = "./processed_dataset"
    try:
        processed_dataset = load_from_disk(dataset_path)
        logging.info(f"Dataset loaded from {dataset_path}")
    except FileNotFoundError:
        logging.error(f"Processed dataset not found at {dataset_path}. Please run data_preprocess.py first.")
        return

    # Load tokenizer and model
    model_checkpoint = "distilbert-base-uncased"
    tokenizer = AutoTokenizer.from_pretrained(model_checkpoint)
    
    # Ensure the label mapping is correct
    label_list = processed_dataset["train"].features["ner_tags"].feature.names
    id2label = {i: label for i, label in enumerate(label_list)}
    label2id = {label: i for i, label in enumerate(label_list)}

    model = AutoModelForTokenClassification.from_pretrained(
        model_checkpoint, 
        num_labels=len(label_list),
        id2label=id2label,
        label2id=label2id
    )

    def tokenize_and_align_labels(examples):
        """Tokenizes text and aligns labels with respect to subwords."""
        tokenized_inputs = tokenizer(examples["tokens"], truncation=True, is_split_into_words=True)
        
        labels = []
        for i, label in enumerate(examples["ner_tags"]):
            word_ids = tokenized_inputs.word_ids(batch_index=i)
            previous_word_idx = None
            label_ids = []
            for word_idx in word_ids:
                if word_idx is None:
                    label_ids.append(-100)
                elif word_idx != previous_word_idx:
                    label_ids.append(label[word_idx])
                else:
                    label_ids.append(-100)
                previous_word_idx = word_idx
            labels.append(label_ids)
        
        tokenized_inputs["labels"] = labels
        return tokenized_inputs

    # Apply tokenization and label alignment
    tokenized_datasets = processed_dataset.map(tokenize_and_align_labels, batched=True)

    # Data collator
    data_collator = DataCollatorForTokenClassification(tokenizer=tokenizer)

    # Metrics computation
    def compute_metrics(p):
        """Computes metrics for evaluation."""
        predictions, labels = p
        predictions = np.argmax(predictions, axis=2)

        true_predictions = [
            [label_list[p] for (p, l) in zip(prediction, label) if l != -100]
            for prediction, label in zip(predictions, labels)
        ]
        true_labels = [
            [label_list[l] for (p, l) in zip(prediction, label) if l != -100]
            for prediction, label in zip(predictions, labels)
        ]

        precision, recall, f1, _ = precision_recall_fscore_support(
            np.hstack(true_labels), np.hstack(true_predictions), average='weighted'
        )
        accuracy = accuracy_score(np.hstack(true_labels), np.hstack(true_predictions))
        
        return {
            "accuracy": accuracy,
            "f1": f1,
            "precision": precision,
            "recall": recall,
        }

    # Training arguments - CPU friendly
    training_args = TrainingArguments(
        output_dir="./receipt_parser_model_checkpoints",
        evaluation_strategy="epoch",
        learning_rate=2e-5,
        per_device_train_batch_size=8,  # Adjusted for CPU
        per_device_eval_batch_size=8,   # Adjusted for CPU
        num_train_epochs=5,             # Reduced for quicker training
        weight_decay=0.01,
        save_total_limit=1,
        push_to_hub=False,
        no_cuda=True  # Ensure CPU is used
    )

    # Initialize Trainer
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized_datasets["train"],
        eval_dataset=tokenized_datasets["test"],
        tokenizer=tokenizer,
        data_collator=data_collator,
        compute_metrics=compute_metrics
    )

    # Train the model
    logging.info("Training the model...")
    trainer.train()

    # Save the best model
    best_model_path = "./receipt_parser_model"
    trainer.save_model(best_model_path)
    tokenizer.save_pretrained(best_model_path)
    logging.info(f"Best model saved to {best_model_path}")

if __name__ == "__main__":
    main()
