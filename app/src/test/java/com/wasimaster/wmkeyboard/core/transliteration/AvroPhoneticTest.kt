package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class AvroPhoneticTest {

    private fun t(input: String) = AvroPhonetic.transliterate(input)

    @Test fun simpleWords() {
        assertEquals("আমি", t("ami"))
        assertEquals("তুমি", t("tumi"))
        assertEquals("ভাল", t("valo"))
        assertEquals("ভাল", t("bhalo"))
        assertEquals("আছি", t("achi"))
        assertEquals("আসি", t("asi"))
    }

    @Test fun sentence() {
        assertEquals("আমি ভালো আছি", t("ami valO achi"))
    }

    @Test fun inherentVowel() {
        // "o" is the inherent vowel: no glyph after a consonant, অ elsewhere.
        assertEquals("করি", t("kori"))
        assertEquals("মন", t("mon"))
        assertEquals("কেমন", t("kemon"))
        assertEquals("অনেক", t("onek"))
    }

    @Test fun aspiratedConsonants() {
        assertEquals("খাই", t("khai"))
        assertEquals("ঘর", t("ghor"))
        assertEquals("ছবি", t("chobi"))
        assertEquals("ধন", t("dhon"))
    }

    @Test fun conjuncts() {
        // Consecutive consonants form conjuncts with hasant where Bangla has one.
        assertEquals("ক্ক", t("kk"))
        assertEquals("স্কুল", t("skul"))
        assertEquals("বন্ধু", t("bondhu"))
        assertEquals("শক্তি", t("shokti"))
    }

    @Test fun consonantsOutsideTheConjunctListStayApart() {
        // Desktop Avro's rule: a pair Bangla does not write as a conjunct is
        // two letters, which is what every present-tense verb needs.
        assertEquals("বলছি", t("bolchi"))
        assertEquals("করব", t("korbo"))
        assertEquals("একদম", t("ekdom"))
        assertEquals("দেখতে", t("dekhte"))
        assertEquals("আজকে", t("ajke"))
        // ...while the fola joins still take any consonant.
        assertEquals("প্রথম", t("prothom"))
        assertEquals("ক্যা", t("kya"))
        assertEquals("দ্বীপ", t("dwIp"))
    }

    @Test fun nTakesTheNasalOfItsClass() {
        assertEquals("অঙ্ক", t("onko"))
        assertEquals("শঙ্খ", t("shonkh"))
        assertEquals("অঞ্চল", t("oncol"))
        assertEquals("পঞ্ছ", t("poncho"))
    }

    @Test fun fixedConjunctKkh() {
        // "kkh" is the conventional Avro key for ক্ষ.
        assertEquals("ক্ষ", t("kkh"))
        assertEquals("ক্ষমা", t("kkhoma"))
        assertEquals("লক্ষ", t("lokkh"))
    }

    @Test fun rephViaDoubleR() {
        // "rr" is reph: single র conjuncting with the next consonant.
        assertEquals("বর্ন", t("borrno"))
        assertEquals("মর্ম", t("morrmo"))
        assertEquals("ধর্ম", t("dhorrmo"))
        assertEquals("আদর্শ", t("adorrsho"))
        assertEquals("নির্ভর", t("nirrvor"))
        assertEquals("কার্য", t("karryo"))
        // A single r stays apart, as on desktop Avro; the reph words people
        // spell that way are in the fixed-spelling list, not the rules.
        assertEquals("ধরম", t("dhormo"))
        assertEquals("করলে", t("korle"))
        // r-fola is still any consonant followed by r.
        assertEquals("ক্র", t("kr"))
        // "rri" keeps ঋ, and "rr" with no consonant after is two র.
        assertEquals("ঋণ", t("rriN"))
        assertEquals("কৃপা", t("krripa"))
        assertEquals("বরর", t("borr"))
    }

    @Test fun aAfterKarGlidesWithAntastyaYo() {
        // "a" right after a kar glides with য় instead of independent আ.
        assertEquals("কিয়ামত", t("kiamot"))
        assertEquals("পিয়ানো", t("pianO"))
        assertEquals("দেয়া", t("dea"))
        assertEquals("মায়া", t("maa"))
        // ...and after any rendered vowel, not just kar.
        assertEquals("ওয়াসি", t("Oasi"))
        assertEquals("ওয়াসি", t("wasi"))
        assertEquals("খাওয়া", t("khaOa"))
        // After an inherent (silent) vowel the independent আ survives.
        assertEquals("কুরআন", t("kuroan"))
        // Capital "A" stays an explicit আ, and word-initial "a" is untouched.
        assertEquals("কিআমত", t("kiAmot"))
        assertEquals("আমি", t("ami"))
    }

    @Test fun onlyCapitalOWritesOKar() {
        // Lowercase "o" is the inherent vowel wherever a consonant precedes
        // it, word-final included — desktop Avro's rule, and the difference
        // between "bhalo" and "bhalO" is the whole point of the shift.
        assertEquals("স্বপ্ন", t("swopno"))
        assertEquals("কষ্ট", t("koShTo"))
        assertEquals("সত্য", t("sotyo"))
        assertEquals("ভাল", t("valo"))
        assertEquals("কাল", t("kalo"))
        assertEquals("ভালো", t("valO"))
        assertEquals("কালো", t("kalO"))
        // A sign closes a consonant's syllable, so the "o" after one is
        // inherent too rather than a fresh অ.
        assertEquals("দুঃখ", t("du:kho"))
    }

    @Test fun anusvaraJoinsNothing() {
        // ং hangs off the syllable in front of it: no hasant either side.
        assertEquals("বাংলা", t("bangla"))
        assertEquals("পংক্তি", t("pongkti"))
        assertEquals("আকাংখা", t("akangkha"))
        assertEquals("অংক", t("ongko"))
        // Before a vowel there is a new syllable to carry, and ঙ্গ carries it,
        // as on desktop Avro; capital "Ng" is the bare ঙ.
        assertEquals("সঙ্গে", t("songe"))
        assertEquals("জঙ্গল", t("jongol"))
        assertEquals("বাঙ্গালি", t("bangali"))
        assertEquals("বাঙালি", t("baNgali"))
        // ...and ঁ and ঃ are the same kind of thing.
        assertEquals("চাঁদ", t("ca^d"))
        assertEquals("দুঃখ", t("du:kh"))
    }

    @Test fun baFolaViaW() {
        assertEquals("স্বাস্থ্য", t("swasthyo"))
        assertEquals("স্বাধীনতা", t("swadhInota"))
        // Off a consonant it is still ও, and the "a" after it still glides.
        assertEquals("ওয়াসি", t("wasi"))
        assertEquals("খাওয়া", t("khaOa"))
    }

    @Test fun nBeforeJIsNio() {
        assertEquals("পাঞ্জাবি", t("panjabi"))
        assertEquals("অঞ্জন", t("onjon"))
    }

    @Test fun capitalsThatSpellNothingReadAsLowercase() {
        // A latched shift must not drop a Latin letter into the word.
        assertEquals("বাংলা", t("Bangla"))
        assertEquals("কেমন", t("Kemon"))
        assertEquals("ছল", t("Cholo"))
        assertEquals("ভাল", t("Valo"))
        // The fold spans the whole rule, so a digraph still matches.
        assertEquals("খাবার", t("KHabar"))
        // Capitals Avro does use are untouched by it.
        assertEquals("টাকা", t("Taka"))
        assertEquals("নদী", t("nodI"))
        assertEquals("ভালো", t("valO"))
        assertEquals("ওকে!", t("OkE!"))
    }

    @Test fun ridmikSpellings() {
        assertEquals("হঠাৎ", t("hoThaTH"))
        assertEquals("দুঃখ", t("duHHkho"))
        assertEquals("চাঁদ", t("caqqd"))
        assertEquals("চাঁদ", t("cacbd"))
        assertEquals("জ্ঞান", t("ggan"))
        // "hs" is the hasant only where a hasant can go — on a consonant —
        // so the h and s of a name are still ordinary letters.
        assertEquals("ন্ব", t("nhsb"))
        // (হ and স are not a conjunct pair, so they stand apart as well.)
        assertEquals("আহসান", t("ahsan"))
    }

    @Test fun clusterBreaksOnlyWithInherentVowel() {
        // Documented invariant: an explicit medial "o" breaks the cluster.
        assertEquals("কলকাতা", t("kolokata"))
    }

    @Test fun vowelSigns() {
        assertEquals("বই", t("boi"))
        assertEquals("নদী", t("nodI"))
        assertEquals("দুই", t("dui"))
    }

    @Test fun retroflex() {
        assertEquals("টাকা", t("Taka"))
        assertEquals("ডাল", t("Dal"))
        assertEquals("বড়", t("boR"))
    }

    @Test fun digitsAndPunctuation() {
        assertEquals("১২৩", t("123"))
        assertEquals("।", t("."))
        assertEquals(".", t(".."))
        assertEquals("৳", t("$"))
    }

    @Test fun anusvaraAndChandrabindu() {
        assertEquals("রং", t("rong"))
        assertEquals("চাঁদ", t("ca^d"))
        assertEquals("দুঃখ", t("du:kh"))
    }

    @Test fun jofolaAndAntastyaYo() {
        // "y" after a consonant is jofola; elsewhere it is য়.
        assertEquals("শ্যাম", t("shyam"))
        assertEquals("ত্যাগ", t("tyag"))
        assertEquals("ব্যাপার", t("byapar"))
        assertEquals("মেয়ে", t("meye"))
        assertEquals("নিয়ে", t("niye"))
        assertEquals("হয়", t("hoy"))
        // At a word start Avro writes ইয়.
        assertEquals("ই\u09DF", t("y"))
        assertEquals("ই\u09DFেস", t("yes"))
        // "Y" never joins the cluster, so য় is reachable after a consonant.
        assertEquals("কয়া", t("kYa"))
    }

    @Test fun backquoteBreaksTheCluster() {
        // Without it two consonants conjunct; with it they stand apart.
        assertEquals("ক্স", t("ks"))
        assertEquals("কস", t("k`s"))
        assertEquals("বন্ধু", t("bondhu"))
        assertEquals("বনধু", t("bon`dhu"))
        // Only the join is broken: a kar after the break still attaches, and
        // so does jofola's fallback to য়.
        assertEquals("কি", t("k`i"))
        assertEquals("কয়া", t("k`ya"))
    }

    @Test fun khandaTa() {
        // A ত with its join taken off is exactly ৎ, so the backquote spells it.
        assertEquals("হঠাৎ", t("hoThat`"))
        // ...and so does the explicit hasant, collapsed at the word end.
        assertEquals("হঠাৎ", t("hoThat,,"))
        // Word-final, not merely string-final.
        assertEquals("হঠাৎ আমি", t("hoThat,, ami"))
        assertEquals("হঠাৎ।", t("hoThat,,."))
        // A hasant with a consonant after it is a live join, not khanda-ta.
        assertEquals("সত্য", t("sotyo"))
        assertEquals("ত্ব", t("t,,b"))
        // Only ত collapses.
        assertEquals("ক্", t("k,,"))
    }

    @Test fun jofolaViaCapitalZ() {
        // "Z" is jofola outright, wherever it lands...
        assertEquals("্য", t("Z"))
        assertEquals("প্যা", t("poZa"))
        // ...including where "y" would have produced the same thing.
        assertEquals("সত্য", t("sotZo"))
        assertEquals("সত্য", t("sotyo"))
        // The difference is after a vowel, where "y" falls back to য়.
        assertEquals("আয়", t("ay"))
        // ...and "aZ" is the অ্যা of অ্যাপ.
        assertEquals("অ্যা", t("aZ"))
        assertEquals("অ্যাপ", t("aZp"))
    }

    @Test fun qIsKa() {
        // Bangla has no q; it types ক like k, capital or not, and "qq" is
        // still the chandrabindu.
        assertEquals("কলম", t("qolom"))
        assertEquals("কলম", t("Qolom"))
        assertEquals("বাক্য", t("baqyo"))
        assertEquals("চাঁদ", t("caqqd"))
    }

    @Test fun oAfterAVowelIsO() {
        assertEquals("আমিও", t("amio"))
        assertEquals("খাও\u09DFা", t("khaoa"))
        assertEquals("দিও", t("dio"))
        // After a consonant it is still the silent inherent vowel.
        assertEquals("ভাল", t("bhalo"))
    }

    @Test fun doubleEIsLongI() {
        assertEquals("কী", t("kee"))
        assertEquals("ঈদ", t("eed"))
    }

    @Test fun wGlidesIntoTheVowelAfterIt() {
        assertEquals("ও\u09DFে", t("we"))
        assertEquals("সাও\u09DFাল", t("sawal"))
    }

    @Test fun xOpensAWordWithE() {
        assertEquals("এক্সরে", t("xre"))
        assertEquals("বাক্স", t("bax"))
        assertEquals("ক্ষ", t("kx"))
    }

    @Test fun plainRBeforeJofolaKeepsItsShape() {
        // The joiner keeps র whole, where "rry" still spells the reph of কার্য.
        assertEquals("র\u200D্যাব", t("rZab"))
        assertEquals("কার্য", t("karryo"))
    }

    @Test fun ellipsisStaysDots() {
        assertEquals("...", t("..."))
    }

    @Test fun mixedTextPassesThrough() {
        assertEquals("ওকে!", t("OkE!"))
    }
}
