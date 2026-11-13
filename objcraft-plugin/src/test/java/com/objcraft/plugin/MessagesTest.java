package com.objcraft.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class MessagesTest {
    @Test
    void loadsSpanishAndEnglishCatalogsAndSupportsLegacyEnglishLocale() {
        final ResourceBundle spanish = Messages.bundleForLanguage("es_ES");
        final ResourceBundle english = Messages.bundleForLanguage("en_EN");
        final ResourceBundle legacyEnglish = Messages.bundleForLanguage("en_US");

        assertEquals(spanish.keySet(), english.keySet());
        assertTrue(spanish.getString("command.reload.start").contains("Recargando"));
        assertTrue(english.getString("command.reload.start").contains("Reloading"));
        assertEquals(english.getString("command.reload.start"),
                legacyEnglish.getString("command.reload.start"));
    }

    @Test
    void rejectsUnsupportedLanguageCodes() {
        assertThrows(IllegalArgumentException.class, () -> Messages.bundleForLanguage("fr_FR"));
    }
}
