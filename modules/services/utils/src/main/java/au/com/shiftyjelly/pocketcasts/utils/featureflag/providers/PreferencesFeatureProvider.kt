package au.com.shiftyjelly.pocketcasts.utils.featureflag.providers

import android.content.Context
import au.com.shiftyjelly.pocketcasts.helper.BuildConfig
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.MIN_PRIORITY
import au.com.shiftyjelly.pocketcasts.utils.featureflag.ModifiableFeatureProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Used to override values for feature flags at runtime in debug builds.
 * See DefaultReleaseFeatureProvider to set feature flag values for release builds.
 */
@Singleton
class PreferencesFeatureProvider @Inject constructor(
    @ApplicationContext context: Context,
) : ModifiableFeatureProvider {
    private val preferences = context.featureFlagsSharedPrefs()

    init {
        resetPersonalFeatureOverrides()
    }

    override val priority = MIN_PRIORITY

    override fun hasFeature(feature: Feature): Boolean = true

    override fun isEnabled(feature: Feature) = preferences.getBoolean(feature.key, feature.defaultValue)

    override fun setEnabled(feature: Feature, enabled: Boolean) = preferences.edit().putBoolean(feature.key, enabled).apply()

    override suspend fun awaitInitialization() = true

    private fun resetPersonalFeatureOverrides() {
        if (!BuildConfig.IS_PERSONAL || preferences.getInt(PERSONAL_DEFAULTS_VERSION_KEY, 0) >= PERSONAL_DEFAULTS_VERSION) return
        preferences.edit()
            .clear()
            .putInt(PERSONAL_DEFAULTS_VERSION_KEY, PERSONAL_DEFAULTS_VERSION)
            .apply()
    }

    private fun Context.featureFlagsSharedPrefs() = this.getSharedPreferences("POCKETCASTS_FEATURE_FLAGS", Context.MODE_PRIVATE)

    private companion object {
        const val PERSONAL_DEFAULTS_VERSION = 1
        const val PERSONAL_DEFAULTS_VERSION_KEY = "personalDefaultsVersion"
    }
}
