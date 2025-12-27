# 📱 FuelFinder Android App

App nativa Android per trovare i benzinai più convenienti in tempo reale durante il viaggio.

## 🚀 Setup Rapido

### 1. Prerequisiti
- Android Studio Arctic Fox o superiore
- Android SDK 24+ (minSdk)
- Google Play Services
- Account Google Cloud Platform (per Maps API)

### 2. Configurazione API Keys

#### Google Maps API Key
1. Vai su [Google Cloud Console](https://console.cloud.google.com/)
2. Crea un nuovo progetto o seleziona esistente
3. Abilita queste API:
   - Maps SDK for Android
   - Directions API
   - Distance Matrix API
   - Places API
4. Crea credenziali → API Key
5. Restrizioni (opzionale ma consigliato):
   - Android apps: aggiungi SHA-1 del tuo keystore
   - API restrictions: seleziona solo le API sopra

#### Aggiungi la API Key al progetto:

**Metodo 1: local.properties (Consigliato)**
```properties
# local.properties (NON committare questo file!)
MAPS_API_KEY=AIzaSy_TUA_CHIAVE_API_QUI
```

**Metodo 2: gradle.properties**
```properties
# gradle.properties
MAPS_API_KEY=AIzaSy_TUA_CHIAVE_API_QUI
```

### 3. Build e Run

```bash
# Clone del repository
git clone https://github.com/tuousername/fuelfinder-android.git
cd fuelfinder-android

# Build debug
./gradlew assembleDebug

# Installa su dispositivo/emulatore
./gradlew installDebug

# Run
./gradlew connectedAndroidTest
```

## 📁 Struttura Progetto

```
app/
├── src/main/java/com/fuelfinder/app/
│   ├── MainActivity.kt           # Activity principale con mappa
│   ├── ApiClient.kt             # Gestione API e Retrofit
│   ├── models/                  # Data classes
│   │   ├── FuelStation.kt
│   │   └── DirectionsResponse.kt
│   ├── adapters/                # RecyclerView adapters
│   │   └── StationAdapter.kt
│   ├── services/                # Background services
│   │   └── LocationTrackingService.kt
│   ├── utils/                   # Utility classes
│   │   ├── DistanceCalculator.kt
│   │   └── PreferencesManager.kt
│   └── dialogs/                 # Custom dialogs
│       └── SettingsDialog.kt
├── src/main/res/
│   ├── layout/                  # Layout XML
│   │   ├── activity_main.xml
│   │   └── item_station.xml
│   ├── drawable/               # Icone e grafiche
│   ├── values/                 # Risorse
│   │   ├── strings.xml
│   │   ├── colors.xml
│   │   └── themes.xml
│   └── xml/                    # Configurazioni
│       └── network_security_config.xml
└── build.gradle                # Dipendenze
```

## 🔧 Configurazione Backend

### Opzione 1: Mock Data (Default)
L'app genera stazioni simulate per testing. Nessuna configurazione necessaria.

### Opzione 2: Backend Reale
Modifica `ApiClient.kt`:

```kotlin
object ApiClient {
    private const val BASE_URL = "https://tuobackend.com/api/"
    // ...
}
```

### Opzione 3: PrezziBenzina Integration
Per integrare con PrezziBenzina.it:

1. Implementa scraping o usa API non ufficiali
2. Crea backend proxy per nascondere API keys
3. Modifica endpoints in `FuelService`

## ⚙️ Features Implementate

### ✅ Core Features
- [x] Tracking GPS real-time
- [x] Ricerca benzinai nel raggio
- [x] Aggiornamento automatico posizioni
- [x] Navigazione con Google Maps
- [x] Selezione tipo carburante
- [x] Ordinamento per prezzo

### 🚧 Features con API Reali
- [ ] Distanze stradali reali (Google Directions)
- [ ] Prezzi reali (PrezziBenzina API)
- [ ] Traffico real-time
- [ ] Orari apertura stazioni

### 📍 Calcolo Distanze Reali

Per abilitare distanze stradali reali invece del raggio:

```kotlin
// In MainActivity.kt
private fun calculateRealDistances(stations: List<FuelStation>) {
    val calculator = RealDistanceCalculator()
    
    stations.forEach { station ->
        lifecycleScope.launch {
            val result = calculator.getRealDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                station.latitude,
                station.longitude
            )
            
            result?.let {
                station.realDistance = it.distanceKm
                station.drivingTime = it.durationMinutes
                // Aggiorna UI
                stationAdapter.notifyDataSetChanged()
            }
        }
    }
}
```

## 🔐 Sicurezza

### API Keys
- **MAI** committare API keys nel repository
- Usa `local.properties` per development
- Per produzione: usa Google Play App Signing

### ProGuard Rules
```proguard
# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }
```

## 📱 Testing

### Emulatore
1. Crea AVD con Google Play Services
2. Simula movimento GPS:
   - Extended controls → Location → Routes

### Dispositivo Fisico
1. Abilita Developer Options
2. Abilita USB Debugging
3. Installa via Android Studio o `adb install`

## 🚀 Deploy

### Google Play Store

1. **Prepara Release Build**
```bash
./gradlew bundleRelease
```

2. **Firma l'App Bundle**
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore release-keystore.jks \
  app/build/outputs/bundle/release/app-release.aab \
  your-key-alias
```

3. **Upload su Play Console**
- Crea app in Google Play Console
- Upload AAB file
- Configura store listing
- Invia per review

## 📊 Monitoraggio

### Firebase Analytics (Opzionale)
```gradle
// In app/build.gradle
implementation 'com.google.firebase:firebase-analytics-ktx:21.5.0'
```

### Crashlytics (Consigliato)
```gradle
implementation 'com.google.firebase:firebase-crashlytics-ktx:18.6.0'
```

## 🐛 Troubleshooting

### Problema: Mappa non si carica
- Verifica API Key in AndroidManifest
- Controlla che Maps SDK sia abilitato su Google Cloud
- Verifica SHA-1 fingerprint se hai restrictions

### Problema: GPS non funziona
- Verifica permessi in Settings
- Su emulatore: simula location
- Controlla che Google Play Services sia aggiornato

### Problema: API calls falliscono
- Verifica connessione internet
- Controlla BASE_URL in ApiClient
- Verifica API keys nel backend

## 📄 Licenza

MIT License - Vedi LICENSE file per dettagli

## 🤝 Contribuire

1. Fork del progetto
2. Crea feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit changes (`git commit -m 'Add AmazingFeature'`)
4. Push al branch (`git push origin feature/AmazingFeature`)
5. Apri Pull Request

## 👨‍💻 Contatti

Francesco - [tuoemail@example.com](mailto:tuoemail@example.com)

---

**Note**: Questa app è in sviluppo attivo. Per segnalazioni o richieste, apri una issue su GitHub.
