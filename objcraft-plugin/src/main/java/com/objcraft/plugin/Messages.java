package com.objcraft.plugin;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import org.bukkit.plugin.java.JavaPlugin;

final class Messages {
    private static final String BUNDLE_NAME = "messages";
    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");
    private static final Locale ENGLISH = Locale.forLanguageTag("en-EN");

    private final JavaPlugin plugin;
    private volatile Catalog catalog = loadCatalog(SPANISH);

    Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        final String configuredLanguage = plugin.getConfig().getString("general.language", "es_ES");
        try {
            catalog = loadCatalog(resolveLocale(configuredLanguage));
            plugin.getLogger().info("Loaded language " + languageName(catalog.locale()) + ".");
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning(exception.getMessage() + " Falling back to es_ES.");
            catalog = loadCatalog(SPANISH);
        }
    }

    String get(String key, Object... arguments) {
        final Catalog current = catalog;
        try {
            return new MessageFormat(current.bundle().getString(key), current.locale()).format(arguments);
        } catch (MissingResourceException exception) {
            throw new IllegalArgumentException("Missing ObjCraft translation key: " + key, exception);
        }
    }

    static ResourceBundle bundleForLanguage(String language) {
        return loadCatalog(resolveLocale(language)).bundle();
    }

    private static Locale resolveLocale(String language) {
        if (language == null) {
            throw new IllegalArgumentException("general.language must be es_ES or en_EN.");
        }
        return switch (language.trim().replace('-', '_').toLowerCase(Locale.ROOT)) {
            case "es", "es_es" -> SPANISH;
            case "en", "en_en", "en_us", "en_gb" -> ENGLISH;
            default -> throw new IllegalArgumentException(
                    "Unsupported general.language '" + language + "'. Use es_ES or en_EN.");
        };
    }

    private static Catalog loadCatalog(Locale locale) {
        final ResourceBundle.Control control =
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
        final ResourceBundle bundle = ResourceBundle.getBundle(
                BUNDLE_NAME, locale, Messages.class.getClassLoader(), control);
        return new Catalog(bundle, locale);
    }

    private static String languageName(Locale locale) {
        return locale.equals(ENGLISH) ? "en_EN" : "es_ES";
    }

    private record Catalog(ResourceBundle bundle, Locale locale) {
    }
}
