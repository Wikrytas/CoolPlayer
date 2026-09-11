// Top-level build file
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Обязателен с Kotlin 2.0. Версия ДОЛЖНА совпадать с версией Kotlin выше.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}