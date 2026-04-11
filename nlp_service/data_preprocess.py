import os
import xml.etree.ElementTree as ET
from datasets import Dataset, DatasetDict, Features, Value, ClassLabel, Sequence
import kagglehub
import logging
from collections import defaultdict

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

def get_bio_tags(category, word_index):
    """Generates BIO tags for a given category and word index."""
    # Standard BIO tagging
    if word_index == 0:
        return f"B-{category.upper()}"
    else:
        return f"I-{category.upper()}"

def parse_cvat_xml(xml_path):
    """
    Parses a single CVAT-style XML annotation file to extract words and their categories,
    grouped by the image they belong to.
    """
    tree = ET.parse(xml_path)
    root = tree.getroot()
    
    # A dictionary to hold data for each image
    image_data = defaultdict(list)

    for image_tag in root.findall('image'):
        image_name = image_tag.get('name')
        
        for box_tag in image_tag.findall('box'):
            label = box_tag.get('label')
            
            # Extract text from the attribute tag
            text_content = ""
            for attribute in box_tag.findall('attribute'):
                if attribute.get('name') == 'text':
                    text_content = attribute.text or ""
                    break
            
            if text_content:
                words = text_content.split()
                for i, word in enumerate(words):
                    try:
                        # Get bounding box coordinates
                        box = [
                            float(box_tag.get('xtl')),
                            float(box_tag.get('ytl')),
                            float(box_tag.get('xbr')),
                            float(box_tag.get('ybr'))
                        ]
                        
                        image_data[image_name].append({
                            "word": word,
                            "category": label,
                            "box": box,
                            "word_index": i
                        })
                    except (ValueError, TypeError) as e:
                        logging.warning(f"Skipping malformed box data in {image_name}: {e}")
                        continue
                        
    return image_data

def create_hf_dataset(data_dir):
    """Creates a Hugging Face Dataset from the parsed XML data."""
    xml_path = os.path.join(data_dir, 'annotations.xml')
    if not os.path.exists(xml_path):
        logging.error(f"annotations.xml not found in {data_dir}")
        return None

    logging.info(f"Processing {xml_path}...")
    image_data = parse_cvat_xml(xml_path)
    
    if not image_data:
        logging.error("No data could be parsed from annotations.xml.")
        return None

    all_receipts = []
    for image_name, words_info in image_data.items():
        if not words_info:
            continue
            
        # Sort words based on their position (top-to-bottom, left-to-right)
        words_info.sort(key=lambda x: (x['box'][1], x['box'][0]))
        
        tokens = [info['word'] for info in words_info]
        # Use a consistent set of tags, and add 'O' for other
        ner_tags = [get_bio_tags(info['category'], info['word_index']) if info['category'] in ['shop', 'date', 'total', 'item'] else 'O' for info in words_info]

        all_receipts.append({"tokens": tokens, "ner_tags": ner_tags, "image_name": image_name})

    if not all_receipts:
        logging.error("No valid receipts could be created from the parsed data.")
        return None

    # Define a fixed set of tags including 'O'
    tag_names = ['O'] + [f'{bio}-{cat}' for bio in ['B', 'I'] for cat in ['SHOP', 'DATE', 'TOTAL', 'ITEM']]
    
    # Define the features for the dataset
    features = Features({
        'tokens': Sequence(Value('string')),
        'ner_tags': Sequence(ClassLabel(names=tag_names)),
        'image_name': Value('string')
    })

    # Convert list of dictionaries to dictionary of lists
    data = {
        "tokens": [receipt["tokens"] for receipt in all_receipts],
        "ner_tags": [receipt["ner_tags"] for receipt in all_receipts],
        "image_name": [receipt["image_name"] for receipt in all_receipts]
    }

    # Create the dataset
    dataset = Dataset.from_dict(data, features=features)
    
    # Split the dataset into training and testing sets
    dataset_dict = dataset.train_test_split(test_size=0.2, seed=42)
    
    return dataset_dict

def main():
    """Main function to run the data preprocessing."""
    logging.info("Starting data preprocessing...")
    
    # Download the dataset
    logging.info("Downloading dataset from Kaggle...")
    try:
        # Use a temporary directory for the download to avoid caching issues
        path = kagglehub.dataset_download("trainingdatapro/ocr-receipts-text-detection")
        logging.info(f"Dataset downloaded to: {path}")
    except Exception as e:
        logging.error(f"Failed to download dataset. Please check your Kaggle API credentials. Error: {e}")
        return

    # Create and save the dataset
    hf_dataset = create_hf_dataset(path)
    
    if hf_dataset:
        save_path = "./processed_dataset"
        if not os.path.exists(save_path):
            os.makedirs(save_path)
        hf_dataset.save_to_disk(save_path)
        logging.info(f"Hugging Face Dataset saved to {save_path}")

if __name__ == "__main__":
    main()
