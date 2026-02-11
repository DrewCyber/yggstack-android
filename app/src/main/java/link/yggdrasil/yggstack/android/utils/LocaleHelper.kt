package link.yggdrasil.yggstack.android.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {
    
    /**
     * Apply locale to context based on language code
     * @param context The context to apply locale to
     * @param languageCode Language code: "system", "en", "ru"
     * @return Updated context with applied locale
     */
    fun applyLocale(context: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            "en" -> Locale.ENGLISH
            "ru" -> Locale("ru")
            else -> getSystemLocale()
        }
        
        return updateResources(context, locale)
    }
    
    /**
     * Get system locale
     */
    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }
    }
    
    /**
     * Update context resources with new locale
     */
    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            context
        }
    }
    
    /**
     * Get locale for language code
     */
    fun getLocaleForLanguage(languageCode: String): Locale {
        return when (languageCode) {
            "en" -> Locale.ENGLISH
            "ru" -> Locale("ru")
            else -> getSystemLocale()
        }
    }
}
