#!/usr/bin/env python3
"""
scripts/clean_dictionary.py - Permissive Dictionary Cleaner for WMKeyboard.

Filters raw wordlists and n-gram corpora (unigrams, bigrams, trigrams) using Zipf frequency scoring,
broken word cleaning, and permissive reference vocabularies. Handles capitalization frequency adjustments
to reattribute sentence-initial capitalized words to lowercase while preserving true proper nouns.

Usage:
    python3 scripts/clean_dictionary.py input.json output.json [options]
    python3 scripts/clean_dictionary.py input.txt output.txt --format plain
"""

import argparse
import json
import math
import re
import sys
import unicodedata
from typing import Dict, List, Optional, Set, Tuple, Union

# Regex patterns for cleaning and validation
URL_PATTERN = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
HTML_ENTITY_PATTERN = re.compile(r"&[a-zA-Z0-9#]+;", re.IGNORECASE)
VALID_WORD_PATTERN = re.compile(r"^[a-zA-Z\u00C0-\u024F\u0980-\u09FF'-]+$")
REPEATED_CHARS_PATTERN = re.compile(r"(.)\1{2,}")
NUMBER_PATTERN = re.compile(r"\d")
CONTROL_CHARS_PATTERN = re.compile(r"[\x00-\x1f\x7f-\x9f\u200b\ufeff]")

# Sentinel token for sentence openers in bigrams/trigrams
SENTENCE_START_TOKEN = "<s>"


def compute_zipf(count: int, total_tokens: int) -> float:
    """Computes Zipf frequency score log10(frequency per billion)."""
    if count <= 0 or total_tokens <= 0:
        return 0.0
    frequency_per_billion = (count / total_tokens) * 1_000_000_000
    return round(math.log10(frequency_per_billion), 2)


def clean_token(token: str, allowed_vocab: Optional[Set[str]] = None) -> Optional[str]:
    """
    Cleans and validates a single word token.
    Strips leading/trailing apostrophes and hyphens, normalizes quotes/accents,
    rejects HTML, URLs, numbers, consecutive punctuation, and invalid characters.
    Returns cleaned token or None if token is invalid/broken.
    """
    if not token:
        return None

    # Preserve sentence start token
    if token == SENTENCE_START_TOKEN:
        return SENTENCE_START_TOKEN

    # Reject control characters and zero-width spaces
    if CONTROL_CHARS_PATTERN.search(token):
        return None

    # Reject URLs, HTML entities, and digits
    if URL_PATTERN.search(token) or HTML_ENTITY_PATTERN.search(token) or NUMBER_PATTERN.search(token):
        return None

    # Normalize Unicode to NFC
    token = unicodedata.normalize("NFC", token)

    # Normalize curly/smart quotes to standard apostrophe
    token = token.replace("’", "'").replace("‘", "'").replace("`", "'")

    # Strip leading/trailing apostrophes and hyphens
    token = token.strip("'-")

    if not token:
        return None

    # Reject consecutive punctuation (e.g. '' or -- or '- or -')
    if "''" in token or "--" in token or "'-" in token or "-'" in token:
        return None

    # Validate character set
    if not VALID_WORD_PATTERN.match(token):
        return None

    # Check for excessive character repetition (3+ in a row)
    # Allow if the word is explicitly in allowed_vocab (e.g. 'zzz' or 'mmm' if allowed)
    if REPEATED_CHARS_PATTERN.search(token):
        if allowed_vocab is None or token.lower() not in allowed_vocab:
            return None

    # Allowed vocabulary filter check (lowercase comparison)
    if allowed_vocab is not None and token.lower() not in allowed_vocab:
        return None

    return token


def adjust_unigram_capitalization(
    raw_counts: Dict[str, int],
    title_ratio_threshold: float = 0.85,
    capital_penalty: float = 1.0,
) -> Dict[str, int]:
    """
    Adjusts capitalization for unigrams.
    Sentence-initial capitalized words (e.g., 'The' vs 'the') are reattributed to lowercase
    unless the word is a genuine Proper Noun (e.g. 'London', 'Monday') or Acronym ('USA').
    """
    # Group counts by lowercase version
    groups: Dict[str, Dict[str, int]] = {}
    for word, count in raw_counts.items():
        w_lower = word.lower()
        if w_lower not in groups:
            groups[w_lower] = {}
        groups[w_lower][word] = groups[w_lower].get(word, 0) + count

    adjusted: Dict[str, int] = {}

    for w_lower, variants in groups.items():
        total_count = sum(variants.values())
        if total_count <= 0:
            continue

        exact_lower_count = variants.get(w_lower, 0)

        # Single letter 'I' exception
        if w_lower == "i":
            i_caps_count = variants.get("I", 0) + variants.get("i", 0)
            adjusted["I"] = max(1, int(i_caps_count * capital_penalty))
            continue

        # Look for Titlecase variant
        title_variant = w_lower.capitalize()
        title_count = variants.get(title_variant, 0)

        # Look for ALL-CAPS variant
        upper_variant = w_lower.upper()
        upper_count = variants.get(upper_variant, 0)

        # Check for genuine ALL-CAPS Acronym (e.g. USA, AI, HTML, length >= 2)
        if len(w_lower) >= 2 and upper_count > 0 and (upper_count / total_count) >= 0.70:
            adjusted[upper_variant] = max(1, int(upper_count * capital_penalty))
            # Put remaining into lowercase if any
            rem_count = total_count - upper_count
            if rem_count > 0:
                adjusted[w_lower] = adjusted.get(w_lower, 0) + rem_count
            continue

        # Check for Proper Noun: Titlecase ratio >= threshold or only Titlecase present
        title_ratio = title_count / total_count
        if title_count > 0 and (title_ratio >= title_ratio_threshold or exact_lower_count == 0):
            # Treat as Proper Noun
            adjusted[title_variant] = max(1, int(title_count * capital_penalty))
            # Any remaining count goes to lowercase if lowercase existed
            if exact_lower_count > 0:
                adjusted[w_lower] = exact_lower_count
        else:
            # Reattribute sentence-initial titlecase count to lowercase
            adjusted[w_lower] = total_count

    return adjusted


def parse_ngram_key(key: str) -> List[str]:
    """Splits an n-gram key string into tokens."""
    return key.strip().split()


def format_ngram_key(tokens: List[str]) -> str:
    """Joins n-gram tokens into a standard space-separated string key."""
    return " ".join(tokens)


def detect_ngram_type(raw_data: Dict[str, int]) -> str:
    """Detects whether raw data contains unigrams, bigrams, or trigrams."""
    if not raw_data:
        return "unigram"

    sample_keys = list(raw_data.keys())[:50]
    token_counts = [len(parse_ngram_key(k)) for k in sample_keys]

    avg_tokens = sum(token_counts) / len(token_counts) if token_counts else 1.0
    if avg_tokens > 2.5:
        return "trigram"
    elif avg_tokens > 1.5:
        return "bigram"
    else:
        return "unigram"


def filter_dictionary(
    raw_word_counts: Dict[str, int],
    allowed_vocab: Optional[Set[str]] = None,
    min_zipf: float = 2.5,
    min_freq: int = 1,
    max_words: int = 50000,
    ngram_type: str = "auto",
    adjust_case: bool = True,
    capital_penalty: float = 1.0,
    title_ratio_threshold: float = 0.85,
) -> Dict[str, int]:
    """
    Cleans, validates, and filters a dictionary / n-gram corpus.
    """
    if ngram_type == "auto":
        ngram_type = detect_ngram_type(raw_word_counts)

    cleaned_raw: Dict[str, int] = {}

    # Step 1: Clean tokens and filter broken words/n-grams
    for key, count in raw_word_counts.items():
        if count < min_freq:
            continue

        tokens = parse_ngram_key(key)
        cleaned_tokens = [clean_token(t, allowed_vocab) for t in tokens]

        # Drop if any token in n-gram is invalid
        if any(t is None for t in cleaned_tokens):
            continue

        clean_key = format_ngram_key([t for t in cleaned_tokens if t is not None])
        if not clean_key:
            continue

        cleaned_raw[clean_key] = cleaned_raw.get(clean_key, 0) + count

    # Step 2: Capitalization adjustments for unigrams
    if ngram_type == "unigram" and adjust_case:
        processed = adjust_unigram_capitalization(
            cleaned_raw,
            title_ratio_threshold=title_ratio_threshold,
            capital_penalty=capital_penalty,
        )
    elif adjust_case:
        # N-grams capitalization adjustment: lowercase sentence-initial words unless <s> or proper noun
        processed = {}
        for key, count in cleaned_raw.items():
            tokens = parse_ngram_key(key)
            adj_tokens = []
            for i, t in enumerate(tokens):
                if t == SENTENCE_START_TOKEN or t.isupper() or t == "I":
                    adj_tokens.append(t)
                elif i == 0 and t.istitle():
                    # sentence initial in n-gram, lower unless proper noun
                    adj_tokens.append(t.lower())
                else:
                    adj_tokens.append(t)
            adj_key = format_ngram_key(adj_tokens)
            processed[adj_key] = processed.get(adj_key, 0) + count
    else:
        processed = cleaned_raw

    # Step 3: Compute Zipf scores and apply min_zipf cutoff
    total_token_count = sum(processed.values())
    filtered = {}

    for key, count in processed.items():
        zipf_score = compute_zipf(count, total_token_count) if total_token_count > 0 else 3.0
        if zipf_score >= min_zipf:
            filtered[key] = max(filtered.get(key, 0), count)

    # Step 4: Sort by frequency descending and cap entries
    sorted_entries = sorted(filtered.items(), key=lambda item: item[1], reverse=True)[:max_words]
    return dict(sorted_entries)


def load_input_data(input_path: str, format_type: str) -> Tuple[Dict[str, int], str]:
    """Loads raw word/n-gram counts from a file (JSON, Plain Text, or Combined)."""
    data: Dict[str, int] = {}
    detected_format = format_type

    with open(input_path, "r", encoding="utf-8") as f:
        content = f.read()

    trimmed = content.strip()
    if format_type == "auto":
        if trimmed.startswith("{") or trimmed.startswith("["):
            detected_format = "json"
        elif "dictionary=" in trimmed or "word=" in trimmed:
            detected_format = "combined"
        else:
            detected_format = "plain"

    if detected_format == "json":
        parsed = json.loads(content)
        if isinstance(parsed, dict):
            for k, v in parsed.items():
                if isinstance(v, int):
                    data[str(k)] = v
                elif isinstance(v, dict) and "count" in v:
                    data[str(k)] = int(v["count"])
        elif isinstance(parsed, list):
            for item in parsed:
                if isinstance(item, dict):
                    word = item.get("word") or item.get("key") or " ".join(item.get("words", []))
                    count = item.get("count", 1)
                    if word:
                        data[str(word)] = int(count)
    elif detected_format == "combined":
        current_word = None
        for line in content.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("dictionary="):
                continue
            if line.startswith("word="):
                parts = line.split(",")
                word = None
                freq = 1
                for p in parts:
                    if p.startswith("word="):
                        word = p[5:]
                    elif p.startswith("f="):
                        freq = int(p[2:]) if p[2:].isdigit() else 1
                if word:
                    current_word = word
                    data[word] = freq
            elif line.startswith("bigram=") and current_word:
                parts = line.split(",")
                bg_word = None
                freq = 1
                for p in parts:
                    if p.startswith("bigram="):
                        bg_word = p[7:]
                    elif p.startswith("f="):
                        freq = int(p[2:]) if p[2:].isdigit() else 1
                if bg_word:
                    data[f"{current_word} {bg_word}"] = freq
    else:  # plain
        for line in content.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                try:
                    count = int(parts[-1])
                    key = " ".join(parts[:-1])
                except ValueError:
                    key = line
                    count = 1
                data[key] = count

    return data, detected_format


def save_output_data(
    cleaned_data: Dict[str, int],
    output_path: str,
    format_type: str,
    ngram_type: str,
):
    """Saves cleaned dictionary/n-gram data in specified format."""
    with open(output_path, "w", encoding="utf-8") as f:
        if format_type == "json":
            json.dump(cleaned_data, f, indent=2, ensure_ascii=False)
        elif format_type == "combined":
            f.write("dictionary=main:clean,locale=en,description=Cleaned Wordlist\n")
            # Group bigrams under words if bigram format
            words_dict: Dict[str, int] = {}
            bigrams_dict: Dict[str, List[Tuple[str, int]]] = {}

            for key, count in cleaned_data.items():
                tokens = parse_ngram_key(key)
                if len(tokens) == 1:
                    words_dict[tokens[0]] = count
                elif len(tokens) == 2:
                    words_dict[tokens[0]] = max(words_dict.get(tokens[0], 1), count)
                    if tokens[0] not in bigrams_dict:
                        bigrams_dict[tokens[0]] = []
                    bigrams_dict[tokens[0]].append((tokens[1], count))

            for word, freq in words_dict.items():
                f.write(f" word={word},f={freq},flags=,originalFreq={freq}\n")
                if word in bigrams_dict:
                    for bg_word, bg_freq in bigrams_dict[word]:
                        f.write(f"  bigram={bg_word},f={bg_freq}\n")
        else:  # plain text
            f.write("# Cleaned WMKeyboard Dictionary / N-gram List\n")
            for key, count in cleaned_data.items():
                f.write(f"{key} {count}\n")


def load_allowed_vocab(vocab_path: str) -> Set[str]:
    """Loads reference allowed vocabulary list from a text or JSON file."""
    vocab: Set[str] = set()
    with open(vocab_path, "r", encoding="utf-8") as f:
        content = f.read().strip()
    if content.startswith("[") or content.startswith("{"):
        data = json.loads(content)
        if isinstance(data, list):
            vocab = {str(item).strip().lower() for item in data}
        elif isinstance(data, dict):
            vocab = {str(item).strip().lower() for item in data.keys()}
    else:
        for line in content.splitlines():
            w = line.strip().lower()
            if w and not w.startswith("#"):
                vocab.add(w.split()[0])
    return vocab


def main():
    parser = argparse.ArgumentParser(
        description="scripts/clean_dictionary.py - Permissive Dictionary Cleaner for WMKeyboard."
    )
    parser.add_argument("input", help="Path to input raw wordlist or n-gram file")
    parser.add_argument("output", nargs="?", default="cleaned_words.json", help="Path to output cleaned file")
    parser.add_argument(
        "--format",
        choices=["auto", "json", "plain", "combined"],
        default="auto",
        help="Input/Output format (default: auto)",
    )
    parser.add_argument(
        "--ngram-type",
        choices=["auto", "unigram", "bigram", "trigram"],
        default="auto",
        help="N-gram type (default: auto)",
    )
    parser.add_argument("--min-zipf", type=float, default=2.5, help="Minimum Zipf frequency score (default: 2.5)")
    parser.add_argument("--min-freq", type=int, default=1, help="Minimum raw count threshold (default: 1)")
    parser.add_argument("--max-words", type=int, default=50000, help="Maximum number of output entries (default: 50000)")
    parser.add_argument("--allowed-vocab", help="Path to reference allowed vocabulary file")
    parser.add_argument(
        "--no-adjust-case",
        dest="adjust_case",
        action="store_false",
        help="Disable capitalization adjustment / sentence-initial merging",
    )
    parser.add_argument(
        "--capital-penalty",
        type=float,
        default=1.0,
        help="Frequency scaling factor for capitalized words (default: 1.0)",
    )
    parser.add_argument(
        "--title-ratio-threshold",
        type=float,
        default=0.85,
        help="Threshold ratio to preserve Titlecase proper nouns (default: 0.85)",
    )
    parser.add_argument("--verbose", action="store_true", help="Enable verbose logging")

    args = parser.parse_args()

    try:
        raw_data, detected_format = load_input_data(args.input, args.format)
        out_format = detected_format if args.format == "auto" else args.format

        allowed_vocab = load_allowed_vocab(args.allowed_vocab) if args.allowed_vocab else None

        if args.verbose:
            print(f"Loaded {len(raw_data)} entries from {args.input} (format: {detected_format})")
            if allowed_vocab:
                print(f"Loaded allowed vocabulary reference with {len(allowed_vocab)} words")

        cleaned = filter_dictionary(
            raw_data,
            allowed_vocab=allowed_vocab,
            min_zipf=args.min_zipf,
            min_freq=args.min_freq,
            max_words=args.max_words,
            ngram_type=args.ngram_type,
            adjust_case=args.adjust_case,
            capital_penalty=args.capital_penalty,
            title_ratio_threshold=args.title_ratio_threshold,
        )

        save_output_data(cleaned, args.output, out_format, args.ngram_type)

        print(f"Cleaned {len(raw_data)} raw entries -> {len(cleaned)} entries saved to {args.output}")

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        if args.verbose:
            import traceback
            traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
