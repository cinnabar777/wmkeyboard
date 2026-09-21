#!/usr/bin/env python3
"""
scripts/test_clean_dictionary.py - Unit tests for scripts/clean_dictionary.py
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from clean_dictionary import (
    SENTENCE_START_TOKEN,
    adjust_unigram_capitalization,
    clean_token,
    compute_zipf,
    detect_ngram_type,
    filter_dictionary,
    load_input_data,
    save_output_data,
)


class TestCleanDictionary(unittest.TestCase):

    def test_compute_zipf(self):
        self.assertEqual(compute_zipf(0, 1000), 0.0)
        self.assertEqual(compute_zipf(100, 0), 0.0)
        # 1,000 out of 1,000,000 tokens = 1,000,000 per billion -> log10(1,000,000) = 6.0
        self.assertEqual(compute_zipf(1000, 1_000_000), 6.0)

    def test_clean_token_valid(self):
        self.assertEqual(clean_token("hello"), "hello")
        self.assertEqual(clean_token("World"), "World")
        self.assertEqual(clean_token("don't"), "don't")
        self.assertEqual(clean_token("state-of-the-art"), "state-of-the-art")
        self.assertEqual(clean_token("café"), "café")
        self.assertEqual(clean_token("বাংলা"), "বাংলা")
        self.assertEqual(clean_token(SENTENCE_START_TOKEN), SENTENCE_START_TOKEN)

    def test_clean_token_stripping_and_quotes(self):
        self.assertEqual(clean_token("'hello'"), "hello")
        self.assertEqual(clean_token("-word-"), "word")
        self.assertEqual(clean_token("’hello’"), "hello")
        self.assertEqual(clean_token("it's"), "it's")

    def test_clean_token_rejections(self):
        # Invalid / broken tokens
        self.assertIsNone(clean_token(""))
        self.assertIsNone(clean_token("   "))
        self.assertIsNone(clean_token("http://example.com"))
        self.assertIsNone(clean_token("www.google.com"))
        self.assertIsNone(clean_token("&amp;"))
        self.assertIsNone(clean_token("word123"))
        self.assertIsNone(clean_token("12345"))
        self.assertIsNone(clean_token("can''t"))
        self.assertIsNone(clean_token("double--hyphen"))
        self.assertIsNone(clean_token("loooove"))  # 3+ repeated characters
        self.assertIsNone(clean_token("hello\u200bworld"))  # Zero width space

    def test_clean_token_allowed_vocab(self):
        vocab = {"hello", "world"}
        self.assertEqual(clean_token("hello", vocab), "hello")
        self.assertEqual(clean_token("Hello", vocab), "Hello")  # case insensitive match
        self.assertIsNone(clean_token("unknown", vocab))

    def test_adjust_unigram_capitalization(self):
        # Sentence-initial 'The' reattributed to 'the'
        raw = {"the": 1000, "The": 300}
        adjusted = adjust_unigram_capitalization(raw)
        self.assertEqual(adjusted, {"the": 1300})

        # Proper Noun 'London' preserved
        raw_pn = {"London": 500}
        adjusted_pn = adjust_unigram_capitalization(raw_pn)
        self.assertEqual(adjusted_pn, {"London": 500})

        # Acronym 'USA' preserved
        raw_acronym = {"USA": 400, "usa": 20}
        adjusted_acronym = adjust_unigram_capitalization(raw_acronym)
        self.assertEqual(adjusted_acronym, {"USA": 400, "usa": 20})

        # Letter 'I' preserved
        raw_i = {"i": 10, "I": 200}
        adjusted_i = adjust_unigram_capitalization(raw_i)
        self.assertEqual(adjusted_i, {"I": 210})

    def test_detect_ngram_type(self):
        self.assertEqual(detect_ngram_type({"the": 100}), "unigram")
        self.assertEqual(detect_ngram_type({"of the": 100, "in a": 50}), "bigram")
        self.assertEqual(detect_ngram_type({"one of the": 100}), "trigram")

    def test_filter_dictionary_unigram(self):
        raw = {
            "the": 10000,
            "The": 2000,
            "http://bad.com": 500,
            "loooove": 300,
            "London": 1500,
        }
        cleaned = filter_dictionary(raw, min_zipf=1.0)
        self.assertIn("the", cleaned)
        self.assertEqual(cleaned["the"], 12000)
        self.assertNotIn("http://bad.com", cleaned)
        self.assertNotIn("loooove", cleaned)
        self.assertIn("London", cleaned)

    def test_filter_dictionary_bigram(self):
        raw = {
            "<s> hello": 5000,
            "of the": 10000,
            "of http://bad.com": 100,
        }
        cleaned = filter_dictionary(raw, min_zipf=1.0, ngram_type="bigram")
        self.assertIn("<s> hello", cleaned)
        self.assertIn("of the", cleaned)
        self.assertNotIn("of http://bad.com", cleaned)

    def test_io_json_format(self):
        raw = {"hello": 100, "world": 50}
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".json") as f:
            json.dump(raw, f)
            f_path = f.name

        try:
            data, fmt = load_input_data(f_path, "auto")
            self.assertEqual(fmt, "json")
            self.assertEqual(data, raw)

            out_path = f_path + ".out.json"
            save_output_data(data, out_path, "json", "unigram")
            with open(out_path, "r") as out_f:
                saved_data = json.load(out_f)
            self.assertEqual(saved_data, raw)
            if os.path.exists(out_path):
                os.remove(out_path)
        finally:
            if os.path.exists(f_path):
                os.remove(f_path)

    def test_io_plain_format(self):
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".txt") as f:
            f.write("hello 100\nworld 50\n# comment\n")
            f_path = f.name

        try:
            data, fmt = load_input_data(f_path, "auto")
            self.assertEqual(fmt, "plain")
            self.assertEqual(data, {"hello": 100, "world": 50})
        finally:
            if os.path.exists(f_path):
                os.remove(f_path)


if __name__ == "__main__":
    unittest.main()
