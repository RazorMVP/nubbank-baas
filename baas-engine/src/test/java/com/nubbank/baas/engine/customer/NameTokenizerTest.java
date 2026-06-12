package com.nubbank.baas.engine.customer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NameTokenizerTest {

    private final NameTokenizer tokenizer = new NameTokenizer("test-encryption-key-exactly-32c!");

    @Test
    void tokensForName_includePrefixesOfEachWord() {
        List<String> tokens = tokenizer.tokensForName("John", "Doe");
        assertThat(tokens).hasSize(5);   // jo,joh,john,do,doe
        assertThat(tokens).contains(tokenizer.queryToken("john"));
        assertThat(tokens).contains(tokenizer.queryToken("jo"));
        assertThat(tokens).contains(tokenizer.queryToken("doe"));
    }

    @Test
    void queryToken_matchesAStoredPrefix() {
        List<String> stored = tokenizer.tokensForName("Johnathan", "Smith");
        assertThat(stored).contains(tokenizer.queryToken("joh"));
        assertThat(stored).doesNotContain(tokenizer.queryToken("zz"));
    }

    @Test
    void tokensForName_isCaseAndAccentInsensitive() {
        assertThat(tokenizer.tokensForName("JOHN", null))
            .contains(tokenizer.queryToken("john"));
    }
}
