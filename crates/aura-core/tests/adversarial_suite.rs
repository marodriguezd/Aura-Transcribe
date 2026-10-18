use aura_core::bigram::{bigram_cosine, compute_bigrams, cosine_similarity_precomputed};
use aura_core::corrector::{best_term, parse_dict};

#[test]
fn test_bigram_empty_and_single_char_invariants() {
    let empty_cases = ["", "a", "Z", "ñ", "ü", " ", "\n", "\t", "🔥", "\u{0000}"];

    for &s in &empty_cases {
        let (map, norm) = compute_bigrams(s);
        assert!(
            map.is_empty(),
            "Expected empty bigram map for input {:?}, got {:?}",
            s,
            map
        );
        assert_eq!(
            norm, 0.0,
            "Expected 0.0 norm for input {:?}, got {}",
            s, norm
        );

        for &s2 in &empty_cases {
            let sim = bigram_cosine(s, s2);
            assert_eq!(
                sim, 0.0,
                "Cosine similarity between {:?} and {:?} must be 0.0, got {}",
                s, s2, sim
            );
            assert!(!sim.is_nan(), "Cosine similarity must not be NaN");
            assert!(!sim.is_infinite(), "Cosine similarity must not be Inf");
        }
    }
}

#[test]
fn test_bigram_symmetry_and_mathematical_bounds() {
    let test_corpus = [
        "",
        "a",
        "aa",
        "aaa",
        "aaaa",
        "madrid",
        "madriz",
        "corazón",
        "corazon",
        "año",
        "ano",
        "anno",
        "cigüeña",
        "cigueña",
        "pingüino",
        "pinguino",
        "seseo",
        "ceceo",
        "yeísmo",
        "yeismo",
        "haber",
        "aber",
        "hello, world!",
        "hello-world",
        "node.js",
        "c++",
        "c#",
        "12345",
        "123",
        "🔥💯🚀",
        "🔥🚀",
        "привет",
        "привет мир",
        "你好世界",
        "世界你好",
        "مرحبا",
        "مرحبا بك",
    ];

    for (i, &a) in test_corpus.iter().enumerate() {
        for (j, &b) in test_corpus.iter().enumerate() {
            let sim_ab = bigram_cosine(a, b);
            let sim_ba = bigram_cosine(b, a);

            // Symmetry property
            assert!(
                (sim_ab - sim_ba).abs() < 1e-12,
                "Symmetry violation between {:?} and {:?}: {} vs {}",
                a,
                b,
                sim_ab,
                sim_ba
            );

            // Bounds property [0.0, 1.0]
            assert!(
                sim_ab >= 0.0,
                "Similarity must be non-negative for {:?} and {:?}, got {}",
                a,
                b,
                sim_ab
            );
            assert!(
                sim_ab <= 1.0 + 1e-9,
                "Similarity must not exceed 1.0 for {:?} and {:?}, got {}",
                a,
                b,
                sim_ab
            );
            assert!(
                !sim_ab.is_nan(),
                "Similarity must not be NaN for {:?} and {:?}",
                a,
                b
            );

            // Reflexivity: if length >= 2, sim(a, a) must be 1.0
            if i == j && a.chars().count() >= 2 {
                assert!(
                    (sim_ab - 1.0).abs() < 1e-6,
                    "Identity similarity must be 1.0 for {:?}, got {}",
                    a,
                    sim_ab
                );
            }
        }
    }
}

#[test]
fn test_bigram_duplicate_runs_and_repeats() {
    let s1 = "aaaa"; // 3 ('a','a')
    let s2 = "aa"; // 1 ('a','a')
    let sim = bigram_cosine(s1, s2);
    // counts: s1={('a','a'): 3}, norm=3; s2={('a','a'): 1}, norm=1.
    // dot = 3 * 1 = 3. norm_a * norm_b = 3 * 1 = 3. sim = 1.0
    assert!((sim - 1.0).abs() < 1e-9);

    let (m, norm) = compute_bigrams("zzzzzzzzzz");
    assert_eq!(m.len(), 1);
    assert_eq!(*m.get(&('z', 'z')).unwrap(), 9);
    assert!((norm - 9.0).abs() < 1e-9);

    // Alternating
    let (m_alt, _) = compute_bigrams("ababab");
    assert_eq!(m_alt.len(), 2);
    assert_eq!(*m_alt.get(&('a', 'b')).unwrap(), 3);
    assert_eq!(*m_alt.get(&('b', 'a')).unwrap(), 2);
}

#[test]
fn test_multibyte_utf8_char_boundaries_safety() {
    let s = "🦀🦀🦀";
    let (m, norm) = compute_bigrams(s);
    assert_eq!(m.len(), 1);
    assert_eq!(*m.get(&('🦀', '🦀')).unwrap(), 2);
    assert!((norm - 2.0).abs() < 1e-9);

    // Mixing 1-byte, 2-byte, 3-byte, 4-byte UTF-8
    let mixed = "añ€🦀";
    let (m2, _) = compute_bigrams(mixed);
    assert_eq!(m2.len(), 3);
    assert!(m2.contains_key(&('a', 'ñ')));
    assert!(m2.contains_key(&('ñ', '€')));
    assert!(m2.contains_key(&('€', '🦀')));
}

#[test]
fn test_bigram_orthographic_tiebreaking_in_corrector() {
    // Both 'casa' and 'caza' map to phonetic key 'kasa' (seseo).
    // If dictionary contains both, when given 'caza', orthographic tiebreaking
    // must favor 'caza' (cosine 1.0) over 'casa' (cosine 0.333).
    let dict_content = r#"
casa
caza
"#;
    let dict = parse_dict(dict_content);

    let chosen_caza = best_term("caza", &dict.single);
    assert_eq!(chosen_caza, Some("caza".to_string()));

    let chosen_casa = best_term("casa", &dict.single);
    assert_eq!(chosen_casa, Some("casa".to_string()));

    // Yeísmo tiebreak: 'pollo' vs 'poyo' (both key 'poyo')
    let dict_content_yeismo = r#"
pollo
poyo
"#;
    let dict_yeismo = parse_dict(dict_content_yeismo);
    assert_eq!(
        best_term("pollo", &dict_yeismo.single),
        Some("pollo".to_string())
    );
    assert_eq!(
        best_term("poyo", &dict_yeismo.single),
        Some("poyo".to_string())
    );
}

#[test]
fn test_large_repetitive_fuzz_stress() {
    // Verify fast zero-allocation iteration without stack exhaustion
    let huge_a = "abcdefghijklmnopqrstuvwxyz".repeat(400); // 10,400 chars
    let huge_b = "abcdefghijklmnopqrstuvwxyz".repeat(200);

    let (ma, na) = compute_bigrams(&huge_a);
    let (mb, nb) = compute_bigrams(&huge_b);
    let sim = cosine_similarity_precomputed(&ma, na, &mb, nb);

    assert!(
        (sim - 1.0).abs() < 1e-4,
        "Identical distributions must have cosine ~ 1.0, got {}",
        sim
    );
}

#[test]
fn test_tokenize_and_diacritics_preservation() {
    use aura_core::corrector::{correct, tokenize, Segment};

    let sample = "¡Hola, señor! ¿Cómo estás hoy en el año 2026? Pingüino y cigüeña.";
    let tokens = tokenize(sample);
    let reconstructed: String = tokens
        .into_iter()
        .map(|seg| match seg {
            Segment::Word(w) => w,
            Segment::Sep(s) => s,
        })
        .collect();
    assert_eq!(reconstructed, sample);

    // Empty and whitespace-only sentences
    let dict = parse_dict("Madrid\nBarcelona");
    assert_eq!(correct("", &dict), "");
    assert_eq!(correct("   \t\n  ", &dict), "   \t\n  ");
}
