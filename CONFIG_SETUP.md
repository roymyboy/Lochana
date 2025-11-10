# OpenAI API Key Configuration

This project uses a configuration file to store the OpenAI API key securely. The actual config file is gitignored and should never be committed to version control.

## Setup Instructions

### Option 1: Using Config File (Recommended for Development)

1. Copy the template file to create your config:
   ```
   cp app/src/main/assets/config.properties.template app/src/main/assets/config.properties
   ```

2. Edit `app/src/main/assets/config.properties` and add your OpenAI API key:
   ```
   OPENAI_API_KEY=sk-your-actual-api-key-here
   ```

3. The app will automatically load the key from this file on startup.

### Option 2: Using Runtime API (Programmatic)

You can also set the API key programmatically at runtime using:
```kotlin
mainActivity.setOpenAIApiKey("sk-your-api-key-here")
```

This will also store the key in SharedPreferences for persistence.

### Option 3: Using Root Config File (Alternative)

Alternatively, you can create a config file in the project root:
```
openai_config.properties
```

Add your key:
```
OPENAI_API_KEY=sk-your-api-key-here
```

Note: This file is also gitignored.

## Security Notes

- ✅ The actual config file (`config.properties`) is in `.gitignore`
- ✅ Only the template file (`config.properties.template`) is committed
- ✅ Never commit files containing real API keys
- ✅ The API key is loaded from assets at runtime, not hardcoded

## Getting Your API Key

1. Go to https://platform.openai.com/api-keys
2. Sign in or create an account
3. Create a new API key
4. Copy the key and add it to your config file

## File Structure

```
app/src/main/assets/
├── config.properties.template  ✅ (committed, safe to share)
└── config.properties          ❌ (gitignored, contains your key)
```

The app will check for the config file in this order:
1. `app/src/main/assets/config.properties` (assets folder)
2. SharedPreferences (from previous runtime setting)

