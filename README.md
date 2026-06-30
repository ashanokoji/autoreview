# AutoReview - Accessibility Service for Automated Form Filling

AutoReview is an Android application that uses Accessibility Services to automate the process of filling out forms or surveys by reading screen content and providing appropriate inputs (star ratings, yes/no answers) for recognized questions.

## Features

- **Accessibility Service**: Runs in the background to monitor screen content changes.
- **Question Mapping**: Identifies questions on screen and matches them against user-defined presets.
- **Smart Input**: Automatically taps buttons (stars, Yes/No) based on matching logic.
- **Persistent Storage**: Uses DataStore to save and load question presets.
- **UI Configuration**: Allows users to define questions, set defaults, and view history.

## Getting Started

### Prerequisites

- Android Studio (or Gradle CLI)
- Android SDK 36 (API 36) or higher

### Installation

1. Clone the repository.
2. Open the project in Android Studio.
3. Build and install the APK on your device.

### Configuration

After installation, you must grant accessibility permissions:

1. Open the app.
2. Tap **Check Permissions**.
3. Tap **Enable Accessibility Settings**.
4. In the system settings, find "AutoReview" and enable the service.
5. Return to the app. It should now show the Main Screen.

## Usage

1. **Main Screen**: Use this screen to start/stop the overlay service.
2. **Presets Screen**: Define your custom questions and their expected answers.
   - **Questions**: A list of questions you want to automate.
   - **Star Rating**: Default star value (1-5) if the question asks for a rating.
   - **Yes/No**: Default binary choice ("Yes" or "No").
3. **Service Operation**:
   - When the overlay service is active, the app monitors screen changes.
   - If a question is recognized, the service will attempt to tap the corresponding answer.
   - If a question is not recognized (e.g., first time seeing it), it will appear in the **Unrecognized Question** screen.

### Advanced: Handling Unrecognized Questions

When the service encounters a question it doesn't recognize, it triggers an Unrecognized Question Screen.

You can use this screen to map the new question to an answer:
1. Enter a **Key** (a unique identifier for this question, often the question text itself).
2. Select the appropriate **Star Value** or **Yes/No** answer.
3. Tap **Save and Update**.

The service will immediately use this new mapping for future occurrences of this question.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM (Model-View-ViewModel)
- **Persistence**: DataStore (ProtoBuf for typed objects)
- **Accessibility**: Android Accessibility Services API
