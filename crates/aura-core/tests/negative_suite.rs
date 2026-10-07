use aura_core::corrector::{correct, parse_dict};

#[test]
fn test_negative_sentences_zero_spurious_substitutions() {
    let dict_content = r#"
# Nombres y términos técnicos
Kubernetes
PostgreSQL
WebRTC
Aura Transcribe
Termux
"#;
    let dict = parse_dict(dict_content);

    // Common Spanish and English everyday sentences that have NO phonetic or semantic
    // overlap with the dictionary terms. None of them should be mutated.
    let negative_inputs = [
        "hola como estas hoy por la manana",
        "el gato camina por el tejado de la casa",
        "estamos preparando el cafe caliente para desayunar",
        "the quick brown fox jumps over the lazy dog",
        "we are planning to go to the supermarket tomorrow afternoon",
        "please make sure to turn off the lights before leaving",
        "hace un dia soleado y agradable para pasear",
        "los ninos juegan en el parque con la pelota",
        "gracias por tu ayuda y colaboracion en este proyecto",
        "it is very important to write reliable and clean unit tests",
    ];

    for input in &negative_inputs {
        let result = correct(input, &dict);
        assert_eq!(
            result, *input,
            "Negative test failed: spurious replacement detected in '{}' -> '{}'",
            input, result
        );
    }
}

#[test]
fn test_exact_and_phonetic_matches_apply_correctly() {
    let dict_content = r#"
Kubernetes
Aura
"#;
    let dict = parse_dict(dict_content);

    // Exact matches or close phonetic misspellings should be corrected
    let test_cases = [
        (
            "tengo un cluster de kubernetes",
            "tengo un cluster de Kubernetes",
        ),
        (
            "aplicacion aura de transcripcion",
            "aplicacion Aura de transcripcion",
        ),
    ];

    for (input, expected) in &test_cases {
        let result = correct(input, &dict);
        assert_eq!(result, *expected);
    }
}
