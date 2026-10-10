# Tabler Icons Usage Guide

## Overview
Tabler Icons is a lightweight, beautiful icon library with 4,500+ icons designed specifically for modern applications. It's now integrated into your ShiftVault project.

## Installation ✅ (Already Done)
The library has been added to your `libs.versions.toml` and `build.gradle.kts` files.

## Usage in Compose

### Basic Usage
```kotlin


// In your Composable
Icon(
    imageVector = TablerIcons.Heart,
    contentDescription = "Heart Icon"
)
```

### Common Icons Examples
```kotlin
// Navigation & UI
TablerIcons.Home
TablerIcons.Menu
TablerIcons.Settings
TablerIcons.Search
TablerIcons.ArrowLeft
TablerIcons.ArrowRight
TablerIcons.ChevronDown
TablerIcons.ChevronUp

// Actions
TablerIcons.Plus
TablerIcons.Minus
TablerIcons.Edit
TablerIcons.Trash
TablerIcons.Check
TablerIcons.X
TablerIcons.Download
TablerIcons.Upload

// Business & Work
TablerIcons.Briefcase
TablerIcons.Calendar
TablerIcons.Clock
TablerIcons.Users
TablerIcons.User
TablerIcons.Mail
TablerIcons.Phone
TablerIcons.Building

// Status & Indicators
TablerIcons.AlertCircle
TablerIcons.InfoCircle
TablerIcons.CheckCircle
TablerIcons.XCircle
TablerIcons.AlertTriangle

// Media & Content
TablerIcons.Photo
TablerIcons.File
TablerIcons.FileText
TablerIcons.Music
TablerIcons.Video

// Social & Communication
TablerIcons.Share
TablerIcons.Heart
TablerIcons.Star
TablerIcons.MessageCircle
TablerIcons.Bell

// Weather & Nature
TablerIcons.Sun
TablerIcons.Moon
TablerIcons.Cloud
TablerIcons.CloudRain

// Transportation
TablerIcons.Car
TablerIcons.Plane
TablerIcons.Train
TablerIcons.Bus

// Health & Lifestyle
TablerIcons.Heartbeat
TablerIcons.Coffee
TablerIcons.Barbell
TablerIcons.Pill

// Education
TablerIcons.School
TablerIcons.Book
TablerIcons.Certificate
```

### Using with IconButton
```kotlin
IconButton(onClick = { /* action */ }) {
    Icon(
        imageVector = TablerIcons.Plus,
        contentDescription = "Add",
        tint = MaterialTheme.colorScheme.primary
    )
}
```

### Accessing All Icons
You can access icons by group:
```kotlin

// Use outline style (default)
TablerIcons.Heart

// All icons are available through TablerIcons object
```

## Benefits
- ✨ **Lightweight**: Only includes icons you actually use
- 🎨 **Consistent Design**: All icons follow the same design language
- 📦 **Easy to Use**: Works seamlessly with Jetpack Compose
- 🔍 **Large Collection**: 4,500+ icons available
- 🚀 **No Custom Configuration**: Works out of the box

## Example: Updated ShiftIcons.kt
Your `ShiftIcons.kt` file has been updated to use Tabler Icons for better visual consistency and a more modern look.

## Finding More Icons
Browse all available icons at: https://tabler-icons.io/

## Performance
The library uses vector graphics and only bundles the icons you import, keeping your APK size small.

