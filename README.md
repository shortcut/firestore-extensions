# Firestore Extensions for Android 
[![](https://jitpack.io/v/shortcut/firestore-extensions.svg)](https://jitpack.io/#shortcut/firestore-extensions)
 
This library provides a collection of extension functions for Firebase Firestore in Android.
These functions aim to simplify common tasks and enhance productivity when working with Firestore databases in your Android applications.


## Features
 - Simplified Querying
 - Type-Safe Data Mapping
 - Streamlined CRUD Operations
 - Integration with Kotlin Coroutines & Flow


## Usage

To integrate these extension functions into your Android project, follow these steps:

1. Add the Jitpack repository to `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io") // <--- This line...
    }
}
```

2. Add the library dependency to your app module's `build.gradle.kts` file:
```kotlin
implementation("com.github.shortcut:firestore-extensions:{VERSION_TAG}")
```

3. Sync your project with gradle
